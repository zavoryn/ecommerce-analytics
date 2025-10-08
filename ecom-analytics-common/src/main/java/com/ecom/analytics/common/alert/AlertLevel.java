package com.ecom.analytics.common.alert;

/**
 * 告警级别 - 对应飞书卡片配色 / PagerDuty severity / 短信优先级。
 */
public enum AlertLevel {

    /** 提示性信息, 例如任务正常完成 */
    INFO,

    /** 业务可降级 / 自愈, 但需要关注 */
    WARN,

    /** 业务不可用 / 数据失真, 需要工程介入 */
    ERROR,

    /** 系统级故障 / 数据丢失, 立刻 P0 处理 */
    CRITICAL
}
