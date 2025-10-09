package com.ecom.analytics.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 全局接入日志过滤器
 * 记录每条请求的 method/path/status/耗时,便于排查大促期接口超时问题(面试稿 2.8)
 */
@Slf4j
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        ServerHttpRequest req = exchange.getRequest();
        return chain.filter(exchange).doFinally(signal -> {
            long cost = System.currentTimeMillis() - start;
            int status = exchange.getResponse().getStatusCode() == null
                    ? -1 : exchange.getResponse().getStatusCode().value();
            log.info("[GW] {} {} -> {} {}ms",
                    req.getMethod(), req.getPath(), status, cost);
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
