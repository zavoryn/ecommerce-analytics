package com.ecom.analytics.common.config;

import com.ecom.analytics.common.exception.GlobalExceptionHandler;
import com.ecom.analytics.common.trace.TraceIdFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

/**
 * 通用 Web 自动装配。
 *
 * 解决 common 模块组件不被业务模块 @SpringBootApplication 默认 scan 的问题:
 * 通过 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
 * 注册本类, Spring Boot 启动时自动 @Import 内含的组件。
 *
 * 仅在 servlet 栈下生效(gateway WebFlux 走自己的 ErrorWebExceptionHandler / TraceIdGatewayFilter)。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Import({GlobalExceptionHandler.class})
public class CommonWebAutoConfig {

    /**
     * 注册 TraceIdFilter, 优先级置顶, 保证所有业务逻辑能读到 MDC.traceId。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> reg = new FilterRegistrationBean<>(new TraceIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
