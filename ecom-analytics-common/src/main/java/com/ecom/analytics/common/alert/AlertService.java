package com.ecom.analytics.common.alert;

/**
 * 告警通道接口 - 把不同实现(飞书 / 钉钉 / PagerDuty / 短信)抽到同一签名。
 *
 * 关键约定:
 *   - 实现内部必须自己 catch 网络异常, 不允许把告警异常向上抛
 *     (告警通道挂了不能拖垮业务)
 *   - title 适合做短摘要(40 字以内), content 适合 Markdown 详情
 */
public interface AlertService {

    /**
     * 发送告警。
     *
     * @param level   告警级别, 决定推送通道 / 染色
     * @param title   标题(短摘要)
     * @param content 正文(支持 Markdown)
     */
    void send(AlertLevel level, String title, String content);
}
