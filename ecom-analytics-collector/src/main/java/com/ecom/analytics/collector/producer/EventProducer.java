package com.ecom.analytics.collector.producer;

import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.dto.UserEventDTO;
import com.ecom.analytics.common.trace.RocketMqTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 事件生产者
 *
 * 设计要点:
 *  - 使用同 deviceId 的有序消息(MessageQueueSelector by deviceId),保证同一用户事件按序消费,
 *    避免漏斗计算时事件乱序导致 windowFunnel 步骤匹配失败;
 *  - 通过 RocketMqTrace.wrap() 将当前线程 MDC.traceId 写入消息 User Property,
 *    下游消费者读取后串联日志链路;
 *  - 发送失败由上层 catch 后写入 LocalBufferFallback 兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void sendUserEvent(UserEventDTO dto) {
        rocketMQTemplate.asyncSendOrderly(
                MqTopics.TOPIC_USER_EVENT,
                RocketMqTrace.wrap(dto),
                dto.getDeviceId(),
                new SendCallback() {
                    @Override
                    public void onSuccess(SendResult sendResult) {
                        // 异步成功:日志略
                    }
                    @Override
                    public void onException(Throwable e) {
                        log.error("send user event failed, requestId={}", dto.getRequestId(), e);
                    }
                }
        );
    }
}
