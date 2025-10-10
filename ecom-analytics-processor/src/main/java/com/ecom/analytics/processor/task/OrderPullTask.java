package com.ecom.analytics.processor.task;

import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.processor.service.OrderPersistService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 订单反向拉取任务（面试稿 2.2 兜底方案 / 双重保险）
 *
 * 【为什么需要这个任务？】
 * MQ（RocketMQ）在极端情况下可能丢消息（Broker 宕机、网络分区、消费超时后消息过期）。
 * 纯依赖 MQ 推送有数据缺失风险，因此用"定时拉取"做第二道兜底：
 *
 *   ┌─────────────┐  MQ推送（主路）  ┌──────────────────┐
 *   │  订单系统    │ ───────────────▶ │ OrderSyncConsumer │
 *   │  (OLTP)     │                   └──────────────────┘
 *   │             │  定时拉取（兜底）  ┌──────────────────┐
 *   │             │ ───────────────▶ │  OrderPullTask    │
 *   └─────────────┘                   └──────────────────┘
 *                                             │ 两者都走
 *                                             ▼
 *                                   OrderPersistService.upsert()
 *                                   （INSERT ON DUPLICATE KEY UPDATE，天然幂等）
 *
 * 【游标机制】
 * lastSyncedOrderId 存 Redis（Key: order:pull:lastOrderId）。
 * 每次拉取时发送该游标，订单系统只返回 orderId > lastSyncedOrderId 的增量。
 * Redis 挂了 → 从 MySQL order_sync 读最大 orderId 兜底恢复游标，不会全量重刷。
 *
 * 【幂等保证】
 * OrderPersistService 使用 INSERT ... ON DUPLICATE KEY UPDATE + version 乐观锁，
 * 即使 MQ 和定时拉取同时写入同一订单也不会冲突。
 *
 * 【大促优化（2.3）】
 * 正常频率 fixedDelay=5min，大促切到 1min（通过 Nacos 动态配置推送），
 * 保证 P99 数据延迟不超过 2min。
 */
@Slf4j
@Component
public class OrderPullTask {

    /**
     * Redis Key：保存上次成功拉取的最大 orderId（游标）
     * 生产环境应加模块前缀：analytics:order:pull:lastOrderId
     */
    private static final String LAST_ORDER_ID_KEY = "analytics:order:pull:lastOrderId";

    /** 单次拉取批量大小（防止一次性拉太多压垮订单系统） */
    private static final int BATCH_SIZE = 200;

    private final OrderPersistService orderPersistService;
    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate mysqlJdbcTemplate;

    /**
     * 显式构造器 —— mysqlJdbcTemplate 为 @Primary，直接注入；redisTemplate 单 bean。
     */
    public OrderPullTask(OrderPersistService orderPersistService,
                         StringRedisTemplate redisTemplate,
                         @Qualifier("mysqlJdbcTemplate") JdbcTemplate mysqlJdbcTemplate) {
        this.orderPersistService = orderPersistService;
        this.redisTemplate = redisTemplate;
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
    }

    /**
     * 每 5 分钟执行一次（大促期间 Nacos 配置可动态缩短到 1min）
     *
     * fixedDelay：上次执行完毕后再等 5min，避免批量任务堆叠。
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void pull() {
        long lastOrderId = getLastOrderId();
        log.info("[OrderPullTask] start pull, lastOrderId={}", lastOrderId);

        try {
            // ── 调用订单系统 HTTP 接口（此处用 MySQL 模拟兜底读，生产替换为 Feign 调用）────
            // 生产代码：List<OrderSyncDTO> orders = orderFeignClient.pullAfter(lastOrderId, BATCH_SIZE);
            List<OrderSyncDTO> orders = fetchFromOrderSystem(lastOrderId, BATCH_SIZE);

            if (orders.isEmpty()) {
                log.debug("[OrderPullTask] no new orders since orderId={}", lastOrderId);
                return;
            }

            long maxOrderId = lastOrderId;
            int successCnt = 0;
            for (OrderSyncDTO dto : orders) {
                try {
                    orderPersistService.upsert(dto);
                    successCnt++;
                    if (dto.getOrderId() != null && dto.getOrderId() > maxOrderId) {
                        maxOrderId = dto.getOrderId();
                    }
                } catch (Exception e) {
                    // 单条失败不阻断，下次会重拉（游标不前进）
                    log.warn("[OrderPullTask] upsert failed orderId={}: {}", dto.getOrderId(), e.getMessage());
                }
            }

            // 只有全部处理后才推进游标，防止部分失败丢单
            if (successCnt == orders.size()) {
                saveLastOrderId(maxOrderId);
                log.info("[OrderPullTask] pulled {} orders, advanced lastOrderId to {}", successCnt, maxOrderId);
            } else {
                log.warn("[OrderPullTask] partial success {}/{}, cursor not advanced to avoid data loss",
                        successCnt, orders.size());
            }

        } catch (Exception e) {
            log.error("[OrderPullTask] pull failed, will retry next tick: {}", e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 私有辅助方法
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 获取游标：先读 Redis，Redis 不可用时从 MySQL 恢复（max orderId）。
     */
    private long getLastOrderId() {
        try {
            String val = redisTemplate.opsForValue().get(LAST_ORDER_ID_KEY);
            if (val != null) {
                return Long.parseLong(val);
            }
        } catch (Exception e) {
            log.warn("[OrderPullTask] Redis unavailable, fallback to MySQL for lastOrderId: {}", e.getMessage());
        }
        // Redis 不可用：从 MySQL 读最大 orderId 作为游标起点
        Long maxId = mysqlJdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(order_id), 0) FROM order_sync", Long.class);
        log.info("[OrderPullTask] recovered lastOrderId from MySQL: {}", maxId);
        return maxId != null ? maxId : 0L;
    }

    /**
     * 持久化游标到 Redis（TTL 30 天，防止长期不重启时 key 消失）。
     */
    private void saveLastOrderId(long orderId) {
        try {
            redisTemplate.opsForValue().set(LAST_ORDER_ID_KEY, String.valueOf(orderId), 30, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("[OrderPullTask] Redis unavailable, lastOrderId not saved: {}", e.getMessage());
        }
    }

    /**
     * 模拟从订单系统拉取增量订单。
     *
     * 生产环境：替换为 Feign Client 调用订单微服务接口，如：
     *   GET /internal/order/pull?afterOrderId={lastOrderId}&limit={BATCH_SIZE}
     *
     * 此处直接读 order_sync 表（兜底：当订单系统不可用时，用数仓自身存量做数据补全）。
     */
    private List<OrderSyncDTO> fetchFromOrderSystem(long lastOrderId, int limit) {
        String sql = "SELECT order_id, user_id, item_id, category, quantity, order_amount, " +
                     "       paid_amount, order_status, version, order_time, update_time " +
                     "FROM order_sync " +
                     "WHERE order_id > ? " +
                     "ORDER BY order_id ASC " +
                     "LIMIT ?";
        try {
            return mysqlJdbcTemplate.query(sql, (rs, rowNum) -> {
                OrderSyncDTO dto = new OrderSyncDTO();
                dto.setOrderId(rs.getLong("order_id"));
                dto.setUserId(rs.getLong("user_id"));
                dto.setItemId(rs.getLong("item_id"));
                dto.setCategory(rs.getString("category"));
                dto.setQuantity(rs.getInt("quantity"));
                dto.setOrderAmount(rs.getBigDecimal("order_amount"));
                dto.setPaidAmount(rs.getBigDecimal("paid_amount"));
                dto.setOrderStatus(rs.getInt("order_status"));
                dto.setVersion(rs.getLong("version"));
                java.sql.Timestamp ot = rs.getTimestamp("order_time");
                if (ot != null) dto.setOrderTime(ot.toLocalDateTime());
                java.sql.Timestamp ut = rs.getTimestamp("update_time");
                if (ut != null) dto.setUpdateTime(ut.toLocalDateTime());
                return dto;
            }, lastOrderId, limit);
        } catch (Exception e) {
            log.error("[OrderPullTask] fetchFromOrderSystem failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
