package com.ecom.analytics.processor.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.ZoneId;

/**
 * 订单数据持久化
 *
 * 用 INSERT ... ON DUPLICATE KEY UPDATE + version 乐观锁,防止取消订单
 * 后又被旧消息覆盖(面试稿 2.5 追问场景:取消 vs 创建乱序)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistService {

    private final JdbcTemplate mysqlJdbcTemplate;

    /**
     * 订单 upsert 入口 - Sentinel 资源 "order:upsert" + version 乐观锁
     *
     * 双重保护:
     *  1) Sentinel: 慢调用比例 > 50% 熔断 10s, 让消息回 MQ 重试 (面试稿 2.5)
     *  2) version 乐观锁: ON DUPLICATE KEY UPDATE 用 IF(VALUES(version) > version)
     *     防止取消订单(version=2) 比创建订单(version=1) 先到, 创建消息后到时不会回滚状态
     */
    @SentinelResource(value = "order:upsert", blockHandler = "upsertBlocked")
    @Transactional
    public void upsert(OrderSyncDTO dto) {
        String sql = "INSERT INTO order_sync " +
                " (order_id, user_id, item_id, category, quantity, order_amount, paid_amount, " +
                "  order_status, version, order_time, update_time) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                " ON DUPLICATE KEY UPDATE " +
                "  order_status = IF(VALUES(version) > version, VALUES(order_status), order_status), " +
                "  paid_amount  = IF(VALUES(version) > version, VALUES(paid_amount),  paid_amount), " +
                "  update_time  = IF(VALUES(version) > version, VALUES(update_time),  update_time), " +
                "  version      = GREATEST(version, VALUES(version))";

        // 防御 null：order_time / update_time 上游未传时，降级用当前时间
        Timestamp orderTs = dto.getOrderTime() != null
                ? Timestamp.from(dto.getOrderTime().atZone(ZoneId.systemDefault()).toInstant())
                : new Timestamp(System.currentTimeMillis());
        Timestamp updateTs = dto.getUpdateTime() != null
                ? Timestamp.from(dto.getUpdateTime().atZone(ZoneId.systemDefault()).toInstant())
                : new Timestamp(System.currentTimeMillis());

        mysqlJdbcTemplate.update(sql,
                dto.getOrderId(), dto.getUserId(), dto.getItemId(), dto.getCategory(),
                dto.getQuantity(), dto.getOrderAmount(), dto.getPaidAmount(),
                dto.getOrderStatus(), dto.getVersion() == null ? 0L : dto.getVersion(),
                orderTs, updateTs);
    }

    /** Sentinel blockHandler - 触发熔断时让 MQ 重试 */
    public void upsertBlocked(OrderSyncDTO dto, BlockException ex) {
        log.warn("[Sentinel] order:upsert blocked rule={} orderId={} - back to MQ retry",
                ex.getClass().getSimpleName(), dto != null ? dto.getOrderId() : null);
        throw new RuntimeException("order:upsert circuit-broken: " + ex.getClass().getSimpleName(), ex);
    }
}
