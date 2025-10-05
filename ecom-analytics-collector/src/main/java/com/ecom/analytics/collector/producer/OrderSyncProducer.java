package com.ecom.analytics.collector.producer;

import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.dto.OrderSyncDTO;
import com.ecom.analytics.common.trace.RocketMqTrace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderSyncProducer {

    private final RocketMQTemplate rocketMQTemplate;

    public void send(OrderSyncDTO dto) {
        // 按 orderId hash 保序,避免取消/退款 vs 创建乱序
        // RocketMqTrace.wrap 注入 traceId, 下游 OrderSyncConsumer 读取串联日志
        rocketMQTemplate.syncSendOrderly(
                MqTopics.TOPIC_ORDER_SYNC,
                RocketMqTrace.wrap(dto),
                String.valueOf(dto.getOrderId())
        );
    }
}
