package com.ecom.analytics.common.trace;

import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * RocketMQ 跨进程 TraceId 透传辅助。
 *
 * 设计上不依赖 rocketmq-spring(避免污染 common 模块的依赖),
 * 但通过 rocketmq-common 提供的 MessageExt 类型读取消费端属性。
 *
 * 用法:
 *   生产端:
 *     rocketMQTemplate.syncSend(topic, RocketMqTrace.wrap(dto));
 *
 *   消费端 (listener 类型须改为 RocketMQListener&lt;MessageExt&gt;):
 *     String tid = RocketMqTrace.extract(msg);
 *     TraceIdHolder.set(tid != null ? tid : TraceIdHolder.generate());
 *     try { ... } finally { TraceIdHolder.clear(); }
 */
public final class RocketMqTrace {

    private RocketMqTrace() {}

    /** 把当前线程 MDC 中的 traceId 写到消息头, 供下游消费者读取 */
    public static <T> Message<T> wrap(T payload) {
        String tid = TraceIdHolder.current();
        if (tid == null) tid = TraceIdHolder.generate();
        return MessageBuilder.withPayload(payload)
                .setHeader(TraceIdHolder.MQ_KEY, tid)
                .build();
    }

    /** 从 RocketMQ 消息读取 traceId 用户属性, 没有返回 null */
    public static String extract(MessageExt msg) {
        return msg.getUserProperty(TraceIdHolder.MQ_KEY);
    }
}
