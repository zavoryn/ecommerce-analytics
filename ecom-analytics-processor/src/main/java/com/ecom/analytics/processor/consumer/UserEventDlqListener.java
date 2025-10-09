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

/** 用户行为流死信队列监听 (%DLQ%GID_USER_EVENT) */
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "%DLQ%" + MqTopics.GROUP_USER_EVENT,
        consumerGroup = "GID_DLQ_USER_EVENT_MONITOR",
        messageModel = MessageModel.CLUSTERING
)
public class UserEventDlqListener implements RocketMQListener<MessageExt> {

    private final AlertService alertService;
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    @Override
    public void onMessage(MessageExt msg) {
        DlqMonitorConsumer.handle("user-event", msg, alertService, metricsProvider);
    }
}
