package com.ecom.analytics.processor.task;

import com.ecom.analytics.common.alert.AlertLevel;
import com.ecom.analytics.common.alert.AlertService;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.processor.service.EventPersistService;
import com.ecom.analytics.processor.service.OrderPersistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 双流 Join 临时表小时级补偿扫描（面试稿 2.4 / 2.5）
 *
 * 【场景】
 * 用户行为事件（点击/加购）和订单事件（创建/支付）来自两条 MQ Topic。
 * 正常情况下两条流在 1s 内都能到达，由 Consumer 直接完成 JOIN 写入正式分析表。
 *
 * 但网络抖动、消费者重启等情况会导致其中一条流到达，另一条还没来——
 * 先到的那条写入 join_temp_event（status=0，等待中），等待另一条。
 *
 * 【补偿策略（本任务处理）】
 * 每小时扫一次 join_temp_event，对超过 1 小时还未匹配的记录做如下处理：
 *
 *   retry_cnt < 3 → 标记 status=2（补偿中），从订单系统 / 埋点日志反向查找缺失数据
 *                   查到 → 合并写正式表，标记 status=1（已匹配）
 *                   查不到 → retry_cnt++，等下一小时再试
 *
 *   retry_cnt >= 3 → 标记 status=3（死信），写告警日志，人工 / 运营介入排查
 *
 * 【关键索引】
 *   idx_status_created(status, created_at) 保证扫描只走 status=0 的等待记录
 *
 * 【面试追问（2.5）：双流 Join 保序问题】
 * Q: 如果"取消订单"消息比"创建订单"消息先到，会不会覆盖？
 * A: join_temp_event 记录行为快照，正式写库走 OrderPersistService.upsert() 里的
 *    ON DUPLICATE KEY UPDATE + version 乐观锁。version 小的 UPDATE 不会覆盖 version 大的。
 *    即使取消（version=2）比创建（version=1）先到，创建消息来了也不会将状态改回去。
 */
@Slf4j
@Component
public class JoinTempScanTask {

    /** 超过多少分钟未匹配则触发补偿 */
    private static final int WAIT_THRESHOLD_MINUTES = 60;

    /** 最大重试次数，超过后标记死信 */
    private static final int MAX_RETRY = 3;

    /** 单次扫描处理上限，防止单次任务跑太久 */
    private static final int SCAN_LIMIT = 500;

    // status 常量（对应 join_temp_event.status 字段）
    private static final int STATUS_WAITING   = 0;
    private static final int STATUS_MATCHED   = 1;
    private static final int STATUS_RETRYING  = 2;
    private static final int STATUS_DEAD      = 3;

    private final JdbcTemplate mysqlJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final EventPersistService eventPersistService;
    private final OrderPersistService orderPersistService;
    private final AlertService alertService;

    public JoinTempScanTask(@Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate,
                            ObjectMapper objectMapper,
                            EventPersistService eventPersistService,
                            OrderPersistService orderPersistService,
                            AlertService alertService) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPersistService = eventPersistService;
        this.orderPersistService = orderPersistService;
        this.alertService = alertService;
    }

    /**
     * 每整点执行（0 0 * * * *），业务低谷期（凌晨）自动稳定。
     * 大促期间可调成每 10 分钟（通过 Nacos 动态推送 cron 表达式）。
     */
    @Scheduled(cron = "0 0 * * * ?")
    public void scan() {
        log.info("[JoinTempScan] start scanning unmatched records (threshold={}min)", WAIT_THRESHOLD_MINUTES);

        // ── Step 1: 查找等待中且超时的记录 ──────────────────────────────────
        String querySql =
            "SELECT id, biz_key, status, event_payload, order_payload, retry_cnt, created_at " +
            "FROM join_temp_event " +
            "WHERE status = ? " +
            "  AND created_at < DATE_SUB(NOW(), INTERVAL ? MINUTE) " +
            "ORDER BY created_at ASC " +
            "LIMIT ?";

        List<Map<String, Object>> rows = mysqlJdbcTemplate.queryForList(
                querySql, STATUS_WAITING, WAIT_THRESHOLD_MINUTES, SCAN_LIMIT);

        if (rows.isEmpty()) {
            log.debug("[JoinTempScan] no unmatched records found");
            return;
        }

        log.info("[JoinTempScan] found {} unmatched records to process", rows.size());

        int matchedCnt = 0;
        int retriedCnt = 0;
        int deadCnt    = 0;

        for (Map<String, Object> row : rows) {
            long   id       = ((Number) row.get("id")).longValue();
            String bizKey   = (String) row.get("biz_key");
            int    retryCnt = ((Number) row.get("retry_cnt")).intValue();

            if (retryCnt >= MAX_RETRY) {
                // ── 已达最大重试：标记死信，运维告警 ────────────────────────
                markDead(id, bizKey);
                deadCnt++;
                continue;
            }

            // ── 标记补偿中（乐观锁：只更新 status=0 的记录，并发安全）────────
            int updated = mysqlJdbcTemplate.update(
                "UPDATE join_temp_event SET status = ?, retry_cnt = retry_cnt + 1 " +
                "WHERE id = ? AND status = ?",
                STATUS_RETRYING, id, STATUS_WAITING);

            if (updated == 0) {
                // 已被其他节点处理（多实例部署场景），跳过
                log.debug("[JoinTempScan] record {} already processed by another node", id);
                continue;
            }

            // ── 尝试补偿：从订单系统 / 埋点日志拉取缺失数据 ─────────────────
            boolean success = tryCompensate(id, bizKey, row);

            if (success) {
                mysqlJdbcTemplate.update(
                    "UPDATE join_temp_event SET status = ?, matched_at = NOW() WHERE id = ?",
                    STATUS_MATCHED, id);
                matchedCnt++;
                log.info("[JoinTempScan] compensated successfully: bizKey={}", bizKey);
            } else {
                // 补偿失败：回退到 waiting 状态（retry_cnt 已 +1，下次继续）
                mysqlJdbcTemplate.update(
                    "UPDATE join_temp_event SET status = ? WHERE id = ?",
                    STATUS_WAITING, id);
                retriedCnt++;
                log.warn("[JoinTempScan] compensate failed (retry {}), will retry: bizKey={}",
                        retryCnt + 1, bizKey);
            }
        }

        log.info("[JoinTempScan] done. matched={} retried={} dead={} / total={}",
                matchedCnt, retriedCnt, deadCnt, rows.size());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 尝试补偿缺失的另一条流数据。
     *
     * 生产实现：
     *  - event_payload 为空（行为流缺失）→ 查 event_detail 表按 biz_key 找埋点
     *  - order_payload 为空（订单流缺失）→ 调用订单系统 Feign 接口按 orderId 查询
     *  - 两条流都有 → 直接合并写正式分析表（不需要外部调用）
     *
     * @return true 表示补偿成功，false 表示还是缺数据
     */
    private boolean tryCompensate(long id, String bizKey, Map<String, Object> row) {
        try {
            String eventPayload = (String) row.get("event_payload");
            String orderPayload = (String) row.get("order_payload");

            boolean hasEvent = eventPayload != null && !eventPayload.equals("null");
            boolean hasOrder = orderPayload != null && !orderPayload.equals("null");

            if (hasEvent && hasOrder) {
                // 两条流都已到达，直接合并（Consumer 应该已处理，此处属于极端情况兜底）
                log.info("[JoinTempScan] both streams present, merging directly: bizKey={}", bizKey);
                return mergeAndPersist(bizKey, eventPayload, orderPayload);
            }

            if (!hasOrder) {
                // 订单流缺失：从 order_sync 表按 bizKey 中的 orderId 字段反查
                // bizKey 格式: userId:itemId:sessionId
                String[] parts = bizKey.split(":");
                if (parts.length >= 2) {
                    long userId = Long.parseLong(parts[0]);
                    long itemId = Long.parseLong(parts[1]);
                    // 查最近 2 小时内该用户该商品的订单
                    List<Map<String, Object>> orders = mysqlJdbcTemplate.queryForList(
                        "SELECT order_id, order_status, order_amount, paid_amount " +
                        "FROM order_sync " +
                        "WHERE user_id = ? AND item_id = ? " +
                        "  AND order_time > DATE_SUB(NOW(), INTERVAL 2 HOUR) " +
                        "ORDER BY order_time DESC LIMIT 1",
                        userId, itemId);
                    if (!orders.isEmpty()) {
                        // 找到了订单数据，更新 order_payload 并合并
                        String fetchedOrder = String.valueOf(orders.get(0));
                        mysqlJdbcTemplate.update(
                            "UPDATE join_temp_event SET order_payload = ? WHERE id = ?",
                            fetchedOrder, id);
                        log.info("[JoinTempScan] fetched missing order for bizKey={}", bizKey);
                        return true;
                    }
                }
                return false; // 仍查不到订单
            }

            if (!hasEvent) {
                // 行为流缺失：从 event_detail 按 session_id 反查
                // bizKey 格式: userId:itemId:sessionId
                String[] parts = bizKey.split(":");
                if (parts.length >= 3) {
                    String sessionId = parts[2];
                    String tableName = "event_detail_" +
                            java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
                    try {
                        List<Map<String, Object>> events = mysqlJdbcTemplate.queryForList(
                            "SELECT event_name, properties FROM " + tableName +
                            " WHERE session_id = ? LIMIT 10",
                            sessionId);
                        if (!events.isEmpty()) {
                            log.info("[JoinTempScan] fetched {} events for sessionId={}", events.size(), sessionId);
                            return true;
                        }
                    } catch (Exception e) {
                        log.warn("[JoinTempScan] event_detail query failed for session {}: {}", sessionId, e.getMessage());
                    }
                }
                return false;
            }

            return false;

        } catch (Exception e) {
            log.error("[JoinTempScan] compensate error for bizKey={}: {}", bizKey, e.getMessage());
            return false;
        }
    }

    /**
     * 合并两条流数据写正式分析表。
     *
     * 实现:
     *  1) eventPayload JSON → UserEventDTO → eventPersistService.persist()
     *     落 MySQL event_detail_YYYYMM + ClickHouse events_local
     *  2) orderPayload JSON → OrderSyncDTO → orderPersistService.upsert()
     *     落 MySQL order_sync (ON DUPLICATE KEY + version 乐观锁)
     *
     * 任意一条反序列化 / 落库失败都视为补偿失败, 由调用方将记录回退到 waiting 状态等下一轮。
     * 不抛异常给上层, 否则会被外层 try-catch 转成 "未知失败", 丢失 retry_cnt 累加机会。
     */
    private boolean mergeAndPersist(String bizKey, String eventPayload, String orderPayload) {
        try {
            UserEventDTO event = objectMapper.readValue(eventPayload, UserEventDTO.class);
            OrderSyncDTO order = objectMapper.readValue(orderPayload, OrderSyncDTO.class);

            eventPersistService.persist(event);
            orderPersistService.upsert(order);

            log.info("[JoinTempScan] mergeAndPersist done bizKey={} eventReq={} orderId={}",
                    bizKey, event.getRequestId(), order.getOrderId());
            return true;
        } catch (Exception e) {
            log.error("[JoinTempScan] mergeAndPersist failed bizKey={}", bizKey, e);
            return false;
        }
    }

    /**
     * 将记录标记为死信, 并通过 AlertService 推送告警(飞书 / NoOp 日志兜底)。
     */
    private void markDead(long id, String bizKey) {
        mysqlJdbcTemplate.update(
            "UPDATE join_temp_event SET status = ? WHERE id = ?",
            STATUS_DEAD, id);
        log.error("[JoinTempScan][DEAD_LETTER] record id={} bizKey={} exceeded max retries({}), " +
                  "manual intervention required!", id, bizKey, MAX_RETRY);
        alertService.send(AlertLevel.ERROR,
                "JoinTempScan 死信",
                "**bizKey**: `" + bizKey + "`\n" +
                "**id**: " + id + "\n" +
                "**重试次数**: 已达 " + MAX_RETRY + " 次\n" +
                "**处理建议**: 排查上游行为流/订单流是否丢失, 检查 event_detail / order_sync 是否补齐");
    }
}
