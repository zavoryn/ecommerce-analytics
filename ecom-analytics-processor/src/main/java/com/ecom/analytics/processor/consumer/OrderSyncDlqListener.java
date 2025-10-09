package com.ecom.analytics.processor.consumer;

import com.ecom.analytics.common.alert.AlertService;
import com.ecom.analytics.common.constant.MqTopics;
import com.ecom.analytics.common.metric.BusinessMetrics;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 订单同步流死信队列监听 (%DLQ%GID_ORDER_SYNC) */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%" + MqTopics.GROUP_ORDER_SYNC,
        consumerGroup = "GID_DLQ_ORDER_SYNC_MONITOR",
        messageModel = MessageModel.CLUSTERING
)
public class OrderSyncDlqListener implements RocketMQListener<MessageExt> {

    private final AlertService alertService;
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    @Override
    public void onMessage(MessageExt msg) {
        DlqMonitorConsumer.handle("order-sync", msg, alertService, metricsProvider);
    }
}
