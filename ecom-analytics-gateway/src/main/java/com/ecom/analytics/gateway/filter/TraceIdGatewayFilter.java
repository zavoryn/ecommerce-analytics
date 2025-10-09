package com.ecom.analytics.gateway.filter;

import com.ecom.analytics.common.trace.TraceIdHolder;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Gateway 全局过滤器 - TraceId 入口/出口透传。
 *
 * 处理顺序最高(HIGHEST_PRECEDENCE), 早于鉴权 / 限流 / 路由生效。
 *
 * 职责:
 *   1) 从入站请求 Header 读 X-Trace-Id, 没有则生成新的
 *   2) Mutate 请求, 将 traceId Header 注入到向下游转发的请求中(确保下游 servlet 端读到)
 *   3) 写到响应 Header(便于前端 / 浏览器 NetTab 排查链路)
 *   4) 写 MDC, 让本网关自身日志(如限流、鉴权失败)也能带 traceId
 *
 * 注意: Reactor 上下文不是 ThreadLocal, MDC 在异步切换线程时可能丢失。
 *       但本网关下日志主要来自过滤器内同步段, 这里 MDC.put / clear 足够覆盖。
 *       真正深度异步追踪建议接入 SkyWalking / Sleuth。
 */
@Slf4j
@Component
public class TraceIdGatewayFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String upstream = exchange.getRequest().getHeaders().getFirst(TraceIdHolder.HEADER);
        String traceId = StringUtils.hasText(upstream) ? upstream : TraceIdHolder.generate();

        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .header(TraceIdHolder.HEADER, traceId)
                .build();
        exchange.getResponse().getHeaders().set(TraceIdHolder.HEADER, traceId);

        MDC.put(TraceIdHolder.MDC_KEY, traceId);
        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(sig -> MDC.remove(TraceIdHolder.MDC_KEY));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
