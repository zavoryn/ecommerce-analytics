package com.ecom.analytics.search.sync;

import com.ecom.analytics.search.repository.doc.OrderEventDoc;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Canal → ES 同步监听器 (面试稿 2.7 追问: 跨月分表深分页 → 异构存储)
 *
 * <h3>数据流</h3>
 * <pre>
 *  MySQL order_sync (核心交易, 跨月分表)
 *        │
 *        │  binlog (row 模式)
 *        ▼
 *  Canal Server (canal.deployer)
 *        │
 *        │  FlatMessage JSON → Kafka
 *        ▼
 *  Kafka topic: ecom.canal.order_sync
 *        │
 *        ▼
 *  本 Listener  (Spring Kafka @KafkaListener)
 *        │
 *        │  INSERT/UPDATE → ES upsert (用 orderId 做 _id, 天然幂等)
 *        │  DELETE        → ES delete
 *        ▼
 *  Elasticsearch order_event_index
 *        │
 *        ▼
 *  运营后台 search_after 深分页 (彻底解放 MySQL)
 * </pre>
 *
 * <h3>开关</h3>
 * 默认关闭, 需启动时通过 ecom.canal.enabled=true 启用。
 * 这样无 Canal Server 的环境(开发/CI) 也能正常启动应用。
 *
 * <h3>幂等</h3>
 * ES 用 orderId 作为 _id, 重放消息自动覆盖, 无需额外去重。
 * 配合 Canal Server 的 at-least-once 投递, 整条链路最终一致。
 *
 * <h3>排序保证</h3>
 * Canal 按 binlog 顺序推送, Kafka topic 用 orderId 作 partition key,
 * 保证同一订单的事件按顺序到达本 Listener; 防止取消(version=2) 比创建(version=1) 先到。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ecom.canal.enabled", havingValue = "true")
public class OrderCanalSyncListener {

    public static final String TARGET_TABLE = "order_sync";

    private final ObjectMapper objectMapper;
    private final ElasticsearchOperations esOperations;

    @KafkaListener(
            topics = "${ecom.canal.topic:ecom.canal.order_sync}",
            groupId = "${ecom.canal.group:ecom-analytics-search-canal}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void onCanalMessage(String payload) {
        try {
            CanalFlatMessage msg = objectMapper.readValue(payload, CanalFlatMessage.class);

            // 过滤: 只关心 order_sync 表的 DML 操作
            if (Boolean.TRUE.equals(msg.getIsDdl())) {
                log.debug("[Canal] skip DDL: {}", msg.getType());
                return;
            }
            if (!TARGET_TABLE.equalsIgnoreCase(msg.getTable())) {
                return;
            }
            List<Map<String, String>> rows = msg.getData();
            if (rows == null || rows.isEmpty()) {
                return;
            }

            for (Map<String, String> row : rows) {
                handleRow(msg.getType(), row);
            }
        } catch (Exception e) {
            // 关键: 这里不能抛出去, 否则 Kafka 会无限重试
            // 写错误日志 + 监控告警, 由人工 / DLQ 兜底
            log.error("[Canal] sync failed, payload={}", payload, e);
        }
    }

    /**
     * 处理单条变更
     *
     * <ul>
     *   <li>INSERT / UPDATE → 反序列化为 OrderEventDoc, 调 ES index API (upsert by _id)</li>
     *   <li>DELETE          → 调 ES delete API, 用 orderId 作 _id</li>
     * </ul>
     */
    private void handleRow(String type, Map<String, String> row) {
        String orderIdStr = row.get("order_id");
        if (orderIdStr == null || orderIdStr.isEmpty()) {
            log.warn("[Canal] row missing order_id, skip: {}", row);
            return;
        }
        Long orderId = Long.parseLong(orderIdStr);
        IndexCoordinates idx = IndexCoordinates.of("order_event_index");

        switch (type.toUpperCase()) {
            case "INSERT":
            case "UPDATE":
                OrderEventDoc doc = toDoc(row, orderId);
                esOperations.save(doc, idx);
                log.info("[Canal] {} synced to ES: orderId={}", type, orderId);
                break;
            case "DELETE":
                esOperations.delete(String.valueOf(orderId), idx);
                log.info("[Canal] DELETE synced to ES: orderId={}", orderId);
                break;
            default:
                log.debug("[Canal] ignore type {}", type);
        }
    }

    /** Canal 行数据 → OrderEventDoc (字段从 row map 取, null 安全) */
    private OrderEventDoc toDoc(Map<String, String> row, Long orderId) {
        OrderEventDoc doc = new OrderEventDoc();
        doc.setOrderId(orderId);
        doc.setUserId(parseLong(row.get("user_id")));
        doc.setItemId(parseLong(row.get("item_id")));
        doc.setCategory(row.get("category"));
        doc.setOrderAmount(parseDecimal(row.get("order_amount")));
        doc.setOrderStatus(parseInt(row.get("order_status")));
        doc.setDeviceId(row.get("device_id"));
        doc.setOrderTime(parseDateTime(row.get("order_time")));
        return doc;
    }

    // ──────────────── 类型安全的解析工具 ────────────────

    private Long parseLong(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }

    private Integer parseInt(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.parseInt(s); } catch (Exception e) { return null; }
    }

    private BigDecimal parseDecimal(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return new BigDecimal(s); } catch (Exception e) { return null; }
    }

    /**
     * Canal 默认 datetime 格式 "yyyy-MM-dd HH:mm:ss" 或时间戳 ms。
     * 这里兼容两种格式。
     */
    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            if (s.matches("\\d+")) {
                return Instant.ofEpochMilli(Long.parseLong(s))
                        .atZone(ZoneId.systemDefault()).toLocalDateTime();
            }
            return LocalDateTime.parse(s.replace(' ', 'T'));
        } catch (Exception e) {
            log.warn("[Canal] datetime parse failed: {}", s);
            return null;
        }
    }
}
