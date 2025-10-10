package com.ecom.analytics.query.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * 缓存三防工具 - 穿透 / 击穿 / 雪崩。
 *
 * 三防策略:
 *   - 穿透(查不存在的 key 直击 DB): 空值缓存 NULL_PLACEHOLDER + 短 TTL
 *   - 击穿(热点 key 失效瞬间并发):  SETNX 互斥锁, 未抢到锁的线程小睡再读
 *   - 雪崩(大量 key 同时失效):     TTL 加 0~10% 随机抖动
 *
 * 用法:
 *   guard.getOrLoad("ranking:gmv:7:null:100", new TypeReference&lt;List&lt;TopItemVO&gt;&gt;(){},
 *                   600, () -&gt; queryFromDb());
 */
@Slf4j
@Component
public class CacheGuard {

    private static final String NULL_PLACEHOLDER = "__NULL__";
    /** 空值占位的 TTL, 短一些避免缓存"无效信息"过久 */
    private static final long NULL_TTL_SECONDS = 60;
    /** 互斥锁 TTL, 给 loader 留够时间 */
    private static final long LOCK_TTL_SECONDS = 5;
    /** 拿不到锁时短睡再读, 给抢到锁的线程写缓存留窗口 */
    private static final long WAIT_RETRY_MILLIS = 50;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public CacheGuard(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 防三穿的 get-or-load。
     *
     * @param key        缓存键
     * @param typeRef    反序列化类型(支持泛型如 List&lt;TopItemVO&gt;)
     * @param ttlSeconds 基础 TTL, 实际会加 0~10% 随机抖动
     * @param loader     缓存未命中时的回源逻辑
     */
    public <T> T getOrLoad(String key, TypeReference<T> typeRef, long ttlSeconds, Supplier<T> loader) {
        String cached = redis.opsForValue().get(key);
        if (cached != null) {
            return NULL_PLACEHOLDER.equals(cached) ? null : deserialize(cached, typeRef);
        }
        // 互斥锁防击穿
        String lockKey = key + ":lock";
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TTL_SECONDS));
        if (Boolean.FALSE.equals(acquired)) {
            sleepQuietly(WAIT_RETRY_MILLIS);
            String afterLock = redis.opsForValue().get(key);
            if (afterLock != null) {
                return NULL_PLACEHOLDER.equals(afterLock) ? null : deserialize(afterLock, typeRef);
            }
            // 锁没拿到但缓存依然空 → 让本线程直接查源(不至于让请求挂死)
        }
        try {
            T data = loader.get();
            long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, ttlSeconds / 10));
            if (data == null) {
                // 防穿透: 写空值占位, 短 TTL
                redis.opsForValue().set(key, NULL_PLACEHOLDER, Duration.ofSeconds(NULL_TTL_SECONDS + jitter));
            } else {
                redis.opsForValue().set(key, serialize(data), Duration.ofSeconds(ttlSeconds + jitter));
            }
            return data;
        } finally {
            redis.delete(lockKey);
        }
    }

    private String serialize(Object data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("CacheGuard serialize failed", e);
            return NULL_PLACEHOLDER;
        }
    }

    private <T> T deserialize(String json, TypeReference<T> typeRef) {
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("CacheGuard deserialize failed, key value will be treated as miss", e);
            return null;
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
