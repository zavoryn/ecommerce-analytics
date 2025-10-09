package com.ecom.analytics.common.metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * 业务自定义 Prometheus 指标 - 与基础 JVM/HTTP 指标并列, 给业务看板用。
 *
 * 指标命名遵循 Prometheus 规范:
 *   - 业务前缀 ecom_*
 *   - 计数器 *_total
 *   - 时间 *_seconds (秒) 或 *_ms (毫秒)
 *
 * 标签设计:
 *   - 高基数 (deviceId / requestId) 严禁加 tag, 会撑爆指标
 *   - 低基数 (event_name / topic / step) 可加 tag
 *
 * 使用示例:
 *   businessMetrics.eventCollect("ok");
 *   try (var sample = businessMetrics.eventCollectTimer("view_item").startSample()) { ... }
 *   businessMetrics.mqConsumeLatency("USER_EVENT", produceTimestamp);
 */
public class BusinessMetrics {

    private final MeterRegistry registry;

    public BusinessMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ═══════════════════════════════════════════════════
    //  埋点采集
    // ═══════════════════════════════════════════════════

    /** ecom_event_collect_total{result="ok|fail"} - 埋点接口调用计数 */
    public void eventCollect(String result) {
        Counter.builder("ecom_event_collect_total")
                .tag("result", result)
                .description("埋点采集接口调用次数")
                .register(registry)
                .increment();
    }

    /** ecom_event_collect_seconds{event_name="..."} - 埋点接口耗时, P50/P95/P99 自动算出 */
    public Timer eventCollectTimer(String eventName) {
        return Timer.builder("ecom_event_collect_seconds")
                .tag("event_name", eventName == null ? "unknown" : eventName)
                .description("埋点采集接口耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);
    }

    // ═══════════════════════════════════════════════════
    //  MQ 消费
    // ═══════════════════════════════════════════════════

    /** ecom_mq_consume_total{topic, result="ok|fail"} - MQ 消费成功失败计数 */
    public void mqConsume(String topic, String result) {
        Counter.builder("ecom_mq_consume_total")
                .tag("topic", topic)
                .tag("result", result)
                .description("MQ 消费成功 / 失败次数")
                .register(registry)
                .increment();
    }

    /** ecom_mq_consume_latency_ms{topic} - 消息从生产到消费的延迟 */
    public void mqConsumeLatency(String topic, long produceEpochMs) {
        long latency = System.currentTimeMillis() - produceEpochMs;
        if (latency < 0) return;   // 时钟漂移防御
        registry.timer("ecom_mq_consume_latency_ms", "topic", topic)
                .record(latency, TimeUnit.MILLISECONDS);
    }

    // ═══════════════════════════════════════════════════
    //  聚合任务
    // ═══════════════════════════════════════════════════

    /** ecom_agg_task_duration_seconds{step} - 聚合任务每步耗时 */
    public Timer aggTaskTimer(String step) {
        return Timer.builder("ecom_agg_task_duration_seconds")
                .tag("step", step)
                .description("凌晨聚合任务步骤耗时")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    /** ecom_agg_task_total{step, result} - 聚合任务执行结果 */
    public void aggTaskResult(String step, String result) {
        Counter.builder("ecom_agg_task_total")
                .tag("step", step)
                .tag("result", result)
                .register(registry)
                .increment();
    }

    // ═══════════════════════════════════════════════════
    //  慢 SQL / 慢查询
    // ═══════════════════════════════════════════════════

    /** ecom_slow_sql_total{service} - 慢 SQL 计数 (执行 > 阈值的查询) */
    public void slowSql(String service) {
        Counter.builder("ecom_slow_sql_total")
                .tag("service", service)
                .register(registry)
                .increment();
    }

    // ═══════════════════════════════════════════════════
    //  缓存
    // ═══════════════════════════════════════════════════

    /** ecom_cache_total{cache_name, level=L1|L2, result=hit|miss} - 缓存命中率 */
    public void cacheAccess(String cacheName, String level, String result) {
        Counter.builder("ecom_cache_total")
                .tag("cache_name", cacheName)
                .tag("level", level)
                .tag("result", result)
                .register(registry)
                .increment();
    }
}
