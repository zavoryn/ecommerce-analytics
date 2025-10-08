package com.ecom.analytics.common.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * 告警通道自动装配。
 *
 * 启用规则:
 *   - 配置了 ecom.alert.lark.webhook → 注入 LarkAlertService
 *   - 否则 → 注入 NoOpAlertService(打 error 日志)
 *
 * 业务侧只需 @Autowired AlertService, 不感知具体实现。
 */
@AutoConfiguration
public class AlertAutoConfig {

    /** 给告警客户端用的 RestTemplate, 超时短一些避免拖累业务 */
    @Bean("alertRestTemplate")
    @ConditionalOnMissingBean(name = "alertRestTemplate")
    public RestTemplate alertRestTemplate() {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        return new RestTemplate(factory);
    }

    @Bean
    @ConditionalOnProperty(name = "ecom.alert.lark.webhook")
    public AlertService larkAlertService(@Value("${ecom.alert.lark.webhook}") String webhook,
                                         RestTemplate alertRestTemplate,
                                         ObjectMapper objectMapper) {
        return new LarkAlertService(webhook, alertRestTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(AlertService.class)
    public AlertService noOpAlertService() {
        return new NoOpAlertService();
    }
}
