package com.ecom.analytics.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * 网关配置
 *
 * 限流 Key:按请求方 IP 做令牌桶限流,防止运营批量刷接口(面试稿 2.10)。
 * 生产中可换成 userId/operator-token 作为 Key 实现用户级限流。
 */
@Configuration
public class GatewayConfig {

    @Bean
    public KeyResolver remoteAddrKeyResolver() {
        return exchange -> Mono.justOrEmpty(
                exchange.getRequest().getRemoteAddress())
                .map(addr -> addr.getAddress().getHostAddress())
                .defaultIfEmpty("unknown");
    }
}
