package com.ecom.analytics.common.constant;

/**
 * RocketMQ Topic 与消费组常量
 */
public final class MqTopics {

    private MqTopics() {}

    /** 用户行为埋点事件 */
    public static final String TOPIC_USER_EVENT = "TOPIC_USER_EVENT";
    public static final String GROUP_USER_EVENT = "GID_USER_EVENT";

    /** 订单同步事件 */
    public static final String TOPIC_ORDER_SYNC = "TOPIC_ORDER_SYNC";
    public static final String GROUP_ORDER_SYNC = "GID_ORDER_SYNC";

    /** 死信队列 */
    public static final String TOPIC_DLQ_USER_EVENT = "DLQ_TOPIC_USER_EVENT";
    public static final String TOPIC_DLQ_ORDER_SYNC = "DLQ_TOPIC_ORDER_SYNC";
}
