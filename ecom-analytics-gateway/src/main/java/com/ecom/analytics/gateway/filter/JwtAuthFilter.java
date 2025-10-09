package com.ecom.analytics.gateway.filter;

import com.ecom.analytics.common.exception.ErrorCode;
import com.ecom.analytics.common.security.JwtProperties;
import com.ecom.analytics.common.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Gateway JWT 鉴权过滤器。
 *
 * 处理顺序:在 TraceIdGatewayFilter 之后, RequestRateLimiter 之前。
 *
 * 白名单(放行无 token):
 *   - /api/collect/event  - 前端埋点 SDK 不持 token
 *   - /api/collect/login  - 登录接口本身就是为了拿 token
 *   - /actuator/health    - 运维健康检查
 *   - /doc.html, /webjars, /v3/api-docs/** - Knife4j 文档
 *
 * 鉴权通过后, 把 userId 注入到 Header X-User-Id 转发给下游服务,
 * 下游服务从 Header 取 userId 即可, 无需再校验 token。
 *
 * 拒绝时返回 401 + 统一 R JSON 格式, 与 GlobalExceptionHandler 风格一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter implements GlobalFilter, Ordered {

    private static final List<String> WHITE_LIST = List.of(
            "/api/collect/event",
            "/api/collect/login",
            "/actuator/health",
            "/doc.html",
            "/webjars",
            "/v3/api-docs",
            "/swagger-ui",
            "/favicon.ico"
    );

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (isWhiteListed(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith("Bearer ")) {
            return reject(exchange, ErrorCode.AUTH_MISSING);
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = JwtUtil.parse(token, jwtProperties.getSecret());
            long userId = JwtUtil.userIdOf(claims);
            ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id", String.valueOf(userId))
                    .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        } catch (Exception e) {
            log.warn("JWT 校验失败 path={} err={}", path, e.getMessage());
            return reject(exchange, ErrorCode.AUTH_INVALID);
        }
    }

    @Override
    public int getOrder() {
        // TraceIdGatewayFilter 是 HIGHEST_PRECEDENCE, 本过滤器排其后
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private boolean isWhiteListed(String path) {
        return WHITE_LIST.stream().anyMatch(path::startsWith);
    }

    private Mono<Void> reject(ServerWebExchange exchange, ErrorCode ec) {
        ServerHttpResponse resp = exchange.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "code", ec.getCode(),
                "msg", ec.getDefaultMsg(),
                "timestamp", System.currentTimeMillis()
        );
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = resp.bufferFactory().wrap(bytes);
            return resp.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("write 401 response failed", e);
            return resp.setComplete();
        }
    }
}
