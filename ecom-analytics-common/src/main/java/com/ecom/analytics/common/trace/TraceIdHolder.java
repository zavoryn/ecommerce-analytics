package com.ecom.analytics.common.trace;

import org.slf4j.MDC;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * TraceId 工具类 - 统一全链路追踪 ID 的来源、存储、清理。
 *
 * 设计:
 *   - HTTP Header 名 X-Trace-Id (业界主流约定)
 *   - MQ User Property 名 traceId  (RocketMQ key 不接受连字符与特殊保留前缀)
 *   - MDC key traceId, 日志 pattern 通过 %X{traceId:-} 引用
 *   - 16 位短 UUID, 日内去重足够, 省日志列宽
 */
public final class TraceIdHolder {

    /** HTTP 透传用 Header */
    public static final String HEADER = "X-Trace-Id";

    /** MQ User Property 透传用 Key */
    public static final String MQ_KEY = "traceId";

    /** MDC 上下文 Key, logback %X{traceId:-} 引用 */
    public static final String MDC_KEY = "traceId";

    private TraceIdHolder() {}

    /**
     * 取或生成 traceId:
     *   - 上游 Header 有值 → 沿用
     *   - 无值 → 新生成 16 位
     */
    public static String getOrGenerate(String upstream) {
        return StringUtils.hasText(upstream) ? upstream : generate();
    }

    public static String generate() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 把 traceId 放进当前线程 MDC, logback 会自动带出 */
    public static void set(String traceId) {
        MDC.put(MDC_KEY, traceId);
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** 当前未持有则生成, 否则返回已有, 不覆盖 */
    public static String currentOrGenerate() {
        String cur = current();
        if (cur != null) return cur;
        String tid = generate();
        set(tid);
        return tid;
    }

    /** 必须在请求处理完毕 / 消息消费完毕调用, 防止线程复用导致 MDC 错位 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
