package com.ecom.analytics.common.metric;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;

/**
 * 业务指标自动装配 - 仅当 MeterRegistry 存在(actuator + 任一 registry)时启用。
 *
 * 与 CommonWebAutoConfig 解耦: 后者只在 servlet 栈生效, BusinessMetrics
 * 对 webflux (gateway) 同样有用, 所以放在独立 auto-config。
 */
@AutoConfiguration
public class MetricsAutoConfig {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    public BusinessMetrics businessMetrics(MeterRegistry registry) {
        return new BusinessMetrics(registry);
    }
}
