package com.ecom.analytics.processor.consumer;

import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.common.metric.BusinessMetrics;
import com.ecom.analytics.common.trace.RocketMqTrace;
import com.ecom.analytics.common.trace.TraceIdHolder;
import com.ecom.analytics.processor.service.EventPersistService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 用户行为事件消费者
 *
 * 设计要点(面试稿 2.5 消息队列可靠性):
 *  - 顺序消费(同 deviceId 入同一队列),保证事件按序写入,漏斗分析依赖此顺序;
 *  - 手动 ACK + 最多重试 3 次,失败入死信队列;
 *  - 监听 MessageExt 而非裸 DTO, 以读取生产端注入的 traceId User Property,
 *    与上游 collector / gateway 日志串联;
 *  - 写入策略:同时落 MySQL 明细表(分月) + ClickHouse 大表(支撑 OLAP 漏斗)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_USER_EVENT,
        consumerGroup = MqTopics.GROUP_USER_EVENT,
        consumeMode = ConsumeMode.ORDERLY,
        messageModel = MessageModel.CLUSTERING,
        // 重试 3 次后, RocketMQ 自动把消息搬到 %DLQ%GROUP_USER_EVENT 死信队列
        // 默认值 -1 即 16 次, 业务场景下太高, 容易堵塞消费组
        maxReconsumeTimes = 3
)
public class UserEventConsumer implements RocketMQListener<MessageExt> {

    private final EventPersistService eventPersistService;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    @Override
    public void onMessage(MessageExt msg) {
        String upstreamTraceId = RocketMqTrace.extract(msg);
        TraceIdHolder.set(upstreamTraceId != null ? upstreamTraceId : TraceIdHolder.generate());
        BusinessMetrics metrics = metricsProvider.getIfAvailable();
        try {
            if (metrics != null) metrics.mqConsumeLatency(msg.getTopic(), msg.getBornTimestamp());
            UserEventDTO dto = objectMapper.readValue(
                    new String(msg.getBody(), StandardCharsets.UTF_8), UserEventDTO.class);
            eventPersistService.persist(dto);
            if (metrics != null) metrics.mqConsume(msg.getTopic(), "ok");
        } catch (Exception e) {
            log.error("persist user event failed, msgId={}, will retry", msg.getMsgId(), e);
            if (metrics != null) metrics.mqConsume(msg.getTopic(), "fail");
            // 抛出后, RocketMQ 框架按 maxReconsumeTimes 重试; 超出进死信
            throw new RuntimeException(e);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
