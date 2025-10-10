package com.ecom.analytics.processor.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.common.util.MonthlyTableUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * 用户行为持久化（双写 MySQL 明细 + ClickHouse OLAP）
 *
 * MySQL 写法：
 *  - 表名按月路由（MonthlyTableUtil），REPLACE INTO 兜底幂等（面试稿 2.6）；
 *  - 扩展字段统一存 ext_info JSON，配合 Generated Column 建虚拟索引（面试稿 1.6）；
 *  - 用 ObjectMapper 序列化 Map → 合法 JSON 字符串（注意：Map.toString() 不是合法 JSON！）。
 *
 * ClickHouse 写法：
 *  - 直接追加 events_local（MergeTree），ORDER BY (device_id, event_date, event_time)；
 *  - 同时写入独立字段（item_id/category/order_id/page_source），
 *    保证 WHERE item_id=? 这类精确过滤走稀疏索引而非全 JSON 解析（面试稿 1.2 单品漏斗）；
 *  - 批量写性能更佳，生产建议聚合 1000 条或 1s flush 一次。脚手架做单条示意。
 *
 * 注意：两个 JdbcTemplate Bean，必须显式构造器 + @Qualifier，
 *       不可混用 @RequiredArgsConstructor（Lombok 不传 @Qualifier）。
 */
@Slf4j
@Service
public class EventPersistService {

    private final JdbcTemplate mysqlJdbcTemplate;
    private final JdbcTemplate ckJdbcTemplate;
    private final ObjectMapper objectMapper;

    public EventPersistService(JdbcTemplate mysqlJdbcTemplate,
                               @Qualifier("clickHouseJdbcTemplate") JdbcTemplate ckJdbcTemplate,
                               ObjectMapper objectMapper) {
        this.mysqlJdbcTemplate = mysqlJdbcTemplate;
        this.ckJdbcTemplate = ckJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 落库主入口 - Sentinel 资源 "event:persist"
     *
     * RT-based 熔断 (面试稿 2.5):
     *  - 规则配在 Nacos / processor-degrade-rules.json, grade=0 (慢调用比例)
     *  - 平均 RT > 1000ms 且 10s 窗口内 50% 请求慢, 触发 10s 熔断
     *  - 触发后所有调用直接进 persistBlocked(), 抛 RuntimeException
     *    → UserEventConsumer 捕获后 RocketMQ 自动延迟重试 (10s/30s/1m/2m...)
     *    → 相当于把流量挡在 MQ 里, 给下游 MySQL/CK 喘息时间, 同时不丢消息
     */
    @SentinelResource(value = "event:persist", blockHandler = "persistBlocked")
    public void persist(UserEventDTO dto) {
        ZonedDateTime t = Instant.ofEpochMilli(dto.getTimestamp()).atZone(ZoneId.systemDefault());
        String table = MonthlyTableUtil.tableOf(t.toLocalDateTime());

        // ── properties → 合法 JSON 字符串（不能用 Map.toString()！）─────────────
        String extInfoJson = toJson(dto.getProperties());

        // ─────────────────────────────────────────────────────────────────────
        //  1) MySQL 明细（REPLACE INTO 幂等，面试稿 2.6）
        // ─────────────────────────────────────────────────────────────────────
        mysqlJdbcTemplate.update(
                "REPLACE INTO " + table +
                " (request_id, device_id, user_id, session_id, event_name, ts, os, app_version, network, ext_info) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                dto.getRequestId(), dto.getDeviceId(), dto.getUserId(), dto.getSessionId(),
                dto.getEventName(),
                Timestamp.from(t.toInstant()),
                dto.getOs(), dto.getAppVersion(), dto.getNetwork(),
                extInfoJson);

        // ─────────────────────────────────────────────────────────────────────
        //  2) ClickHouse 大表（独立字段 + properties JSON，支持 WHERE item_id=? 精确过滤）
        //
        //  关键：item_id / category / order_id / page_source 作为独立列存储，
        //        而不是只存在 properties JSON 里。原因：
        //    ① CK 稀疏索引按 ORDER BY (device_id, event_date, event_time) 排序，
        //       WHERE item_id=? 走的是 skipIndex / 全 Granule 扫描，远比 JSON 解析快。
        //    ② itemFunnel 查询 WHERE item_id=? 必须用独立列才能命中数据。
        // ─────────────────────────────────────────────────────────────────────
        Map<String, Object> props = dto.getProperties();
        long   itemId     = extractLong(props, "item_id");
        String category   = extractStr(props,  "category");
        long   orderId    = extractLong(props, "order_id");
        String pageSource = extractStr(props,  "page_source");

        ckJdbcTemplate.update(
                "INSERT INTO ecom_analytics.events_local " +
                "(event_date, event_time, device_id, user_id, session_id, event_name, " +
                " item_id, category, order_id, page_source, properties, os, app_version, network) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                java.sql.Date.valueOf(t.toLocalDate()),
                Timestamp.from(t.toInstant()),
                dto.getDeviceId(),
                dto.getUserId() == null ? 0L : dto.getUserId(),
                dto.getSessionId(),
                dto.getEventName(),
                itemId,
                category,
                orderId,
                pageSource,
                extInfoJson,
                dto.getOs()        == null ? "" : dto.getOs(),
                dto.getAppVersion() == null ? "" : dto.getAppVersion(),
                dto.getNetwork()    == null ? "" : dto.getNetwork());
    }

    /**
     * Sentinel blockHandler - 熔断 / 限流时被调用。
     *
     * 策略: 主动抛 RuntimeException, 让 RocketMQ 把消息按延迟级别自动重试。
     * 比 fallback 静默丢弃更安全: 数据不丢, 只是延迟落库。
     */
    public void persistBlocked(UserEventDTO dto, BlockException ex) {
        log.warn("[Sentinel] event:persist blocked rule={} requestId={} - back to MQ retry",
                ex.getClass().getSimpleName(), dto != null ? dto.getRequestId() : null);
        throw new RuntimeException("event:persist circuit-broken: " + ex.getClass().getSimpleName(), ex);
    }

    // ─── 工具方法 ───────────────────────────────────────────────────

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return "{}";
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("serialize properties to json failed, fallback to {{}}", e);
            return "{}";
        }
    }

    private long extractLong(Map<String, Object> props, String key) {
        if (props == null) return 0L;
        Object v = props.get(key);
        if (v == null) return 0L;
        if (v instanceof Number) return ((Number) v).longValue();
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0L; }
    }

    private String extractStr(Map<String, Object> props, String key) {
        if (props == null) return "";
        Object v = props.get(key);
        return v == null ? "" : v.toString();
    }
}
