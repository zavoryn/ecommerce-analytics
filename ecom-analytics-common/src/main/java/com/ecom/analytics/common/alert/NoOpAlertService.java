package com.ecom.analytics.common.alert;

import lombok.extern.slf4j.Slf4j;

/**
 * 兜底告警实现 - 没配置任何告警通道时使用, 只打日志, 保证业务调用 alertService.send() 不会 NPE。
 */
@Slf4j
public class NoOpAlertService implements AlertService {

    @Override
    public void send(AlertLevel level, String title, String content) {
        // 没配告警通道时降级到 ERROR 日志, 至少 ELK / SkyWalking 能扫到
        log.error("[ALERT-NOOP] level={} title={} content={}", level, title, content);
    }
}
