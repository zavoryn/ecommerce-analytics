package com.ecom.analytics.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Servlet 栈 TraceId 入口过滤器。
 *
 * 处理顺序最高 (HIGHEST_PRECEDENCE), 保证在业务过滤器 / 日志切面之前生效。
 *
 * 责任:
 *   1) 从 Request Header X-Trace-Id 读取上游传入的 traceId, 没有则生成
 *   2) 写入 MDC (logback %X{traceId:-} 可见)
 *   3) 回写 Response Header (便于前端 / 上游 / 浏览器 NetTab 排查)
 *   4) finally 清理 MDC, 防止线程池复用串号
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = TraceIdHolder.getOrGenerate(req.getHeader(TraceIdHolder.HEADER));
        TraceIdHolder.set(traceId);
        resp.setHeader(TraceIdHolder.HEADER, traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            TraceIdHolder.clear();
        }
    }
}
