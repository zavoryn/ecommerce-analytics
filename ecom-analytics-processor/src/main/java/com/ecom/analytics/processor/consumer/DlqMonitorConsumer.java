package com.ecom.analytics.processor.consumer;

import com.ecom.analytics.common.alert.AlertLevel;
import com.ecom.analytics.common.alert.AlertService;
import com.ecom.analytics.common.metric.BusinessMetrics;
import com.ecom.analytics.common.trace.RocketMqTrace;
import com.ecom.analytics.common.trace.TraceIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;

/**
 * 死信处理共用方法。
 *
 * RocketMQ 死信机制:
 *  消费者重试达 maxReconsumeTimes (本项目设为 3) 仍失败的消息, 由 Broker 自动转入死信 topic,
 *  topic 名固定为 "%DLQ%" + consumerGroup, 例如 %DLQ%GID_USER_EVENT。
 *
 * 处理策略:
 *  - 不再重试(否则就是无限循环), 只打日志 + 推飞书告警
 *  - 让运维 / 业务人工介入决定: 修数据再 resend, 或永久丢弃
 *  - 指标: ecom_mq_consume_total{topic=&lt;dlq&gt;, result="dlq"} 计数
 *
 * 实际监听类见同包下:
 *   - UserEventDlqListener  监听 %DLQ%GID_USER_EVENT
 *   - OrderSyncDlqListener  监听 %DLQ%GID_ORDER_SYNC
 */
@Slf4j
final class DlqMonitorConsumer {

    private DlqMonitorConsumer() {}

    static void handle(String stream, MessageExt msg,
                       AlertService alertService,
                       ObjectProvider<BusinessMetrics> metricsProvider) {
        String upstream = RocketMqTrace.extract(msg);
        TraceIdHolder.set(upstream != null ? upstream : TraceIdHolder.generate());
        try {
            String body = new String(msg.getBody(), StandardCharsets.UTF_8);
            log.error("[DLQ-{}] msgId={} keys={} body={}",
                    stream, msg.getMsgId(), msg.getKeys(), body);

            alertService.send(AlertLevel.ERROR,
                    "MQ 死信告警-" + stream,
                    "**stream**: " + stream + "\n" +
                    "**msgId**: `" + msg.getMsgId() + "`\n" +
                    "**topic**: `" + msg.getTopic() + "`\n" +
                    "**重试**: 已达 3 次最大重试\n" +
                    "**消息体**: ```" + body + "```\n" +
                    "**处理建议**: 排查 persist 逻辑, 修数据后 resend 或永久丢弃");

            BusinessMetrics metrics = metricsProvider.getIfAvailable();
            if (metrics != null) metrics.mqConsume(msg.getTopic(), "dlq");
        } finally {
            TraceIdHolder.clear();
        }
    }
}
