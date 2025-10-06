package com.ecom.analytics.collector.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 幂等服务
 *
 * 两层兜底:
 *  1. Redis SETNX requestId,5min 过期 —— 高频拦截;
 *  2. DB 唯一键(device_id + event_name + ts + biz_key)REPLACE INTO —— 终极保底。
 *
 * Redis 宕机时,自动放行,由 DB 唯一键兜底(性能稍降但不重复落库)。
 */
@Service
@RequiredArgsConstructor
public class IdempotentService {

    private static final String KEY_PREFIX = "collect:req:";
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public boolean tryAcquire(String requestId) {
        if (requestId == null || requestId.isEmpty()) return true;
        try {
            Boolean ok = redis.opsForValue().setIfAbsent(KEY_PREFIX + requestId, "1", TTL);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // Redis 宕机降级:放行,由 DB 唯一键兜底
            return true;
        }
    }
}
