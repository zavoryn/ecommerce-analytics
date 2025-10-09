package com.ecom.analytics.gateway.config;

import com.ecom.analytics.common.security.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 启用 JwtProperties 配置绑定。
 *
 * JwtProperties 在 common 模块,但它是 @ConfigurationProperties 而非 @Component,
 * 需要在 gateway 这边显式 @EnableConfigurationProperties 才能注入。
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {
}
