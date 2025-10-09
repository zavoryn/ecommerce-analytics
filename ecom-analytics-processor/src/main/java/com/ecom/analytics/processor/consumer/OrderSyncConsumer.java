package com.ecom.analytics.processor.consumer;

import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.common.metric.BusinessMetrics;
import com.ecom.analytics.common.trace.RocketMqTrace;
import com.ecom.analytics.common.trace.TraceIdHolder;
import com.ecom.analytics.processor.service.OrderPersistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 订单同步消费者
 *
 * 与用户行为消费者做"双流 Join"(面试稿 2.4 最终一致性):
 *  - 销售数据和用户下单行为分别采集,只有同 user_id + order_id 两条数据都到齐才写入分析表;
 *  - 否则暂存 join_temp 表,由 JoinTemp1HourScanTask 定时校验(见 task 包)。
 *
 * 监听 MessageExt 以读取上游 traceId, 串联 gateway/collector/processor 全链路日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_ORDER_SYNC,
        consumerGroup = MqTopics.GROUP_ORDER_SYNC,
        consumeMode = ConsumeMode.ORDERLY,
        // 订单消息重试 3 次后进 %DLQ%GROUP_ORDER_SYNC
        maxReconsumeTimes = 3
)
public class OrderSyncConsumer implements RocketMQListener<MessageExt> {

    private final OrderPersistService orderPersistService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    @Override
    public void onMessage(MessageExt msg) {
        String upstreamTraceId = RocketMqTrace.extract(msg);
        TraceIdHolder.set(upstreamTraceId != null ? upstreamTraceId : TraceIdHolder.generate());
        BusinessMetrics metrics = metricsProvider.getIfAvailable();
        try {
            if (metrics != null) metrics.mqConsumeLatency(msg.getTopic(), msg.getBornTimestamp());
            OrderSyncDTO dto = objectMapper.readValue(
                    new String(msg.getBody(), StandardCharsets.UTF_8), OrderSyncDTO.class);
            orderPersistService.upsert(dto);
            if (metrics != null) metrics.mqConsume(msg.getTopic(), "ok");
        } catch (Exception e) {
            log.error("upsert order sync failed, msgId={}", msg.getMsgId(), e);
            if (metrics != null) metrics.mqConsume(msg.getTopic(), "fail");
            throw new RuntimeException(e);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
