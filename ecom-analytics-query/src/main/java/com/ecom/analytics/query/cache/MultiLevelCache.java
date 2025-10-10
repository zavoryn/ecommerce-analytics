package com.ecom.analytics.query.cache;

import com.ecom.analytics.common.metric.BusinessMetrics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * 多级缓存: Caffeine 本地 (L1) + Redis 远程 (L2 via CacheGuard)。
 *
 * 查询路径:
 *   L1 命中 → 直接返回 (微秒级)
 *   L1 miss → 查 L2 (CacheGuard 含三防) → 回填 L1
 *
 * 适用场景:
 *   排行榜 / 运营大盘 / 热搜词等读多写少, 强容忍 1 min 内陈旧的查询。
 *
 * 不适用场景:
 *   订单 / 用户身份等强一致场景。
 *
 * 数据一致性: L1 TTL 1 分钟, 写后 1 分钟自然过期。强一致需求请走 evict() 主动清除。
 */
@Slf4j
@Component
public class MultiLevelCache {

    private final Cache<String, Object> l1 = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .recordStats()
            .build();

    private final CacheGuard guard;
    private final ObjectProvider<BusinessMetrics> metricsProvider;

    public MultiLevelCache(CacheGuard guard, ObjectProvider<BusinessMetrics> metricsProvider) {
        this.guard = guard;
        this.metricsProvider = metricsProvider;
    }

    public <T> T getOrLoad(String cacheName, String key,
                           TypeReference<T> typeRef, long redisTtlSeconds, Supplier<T> loader) {
        Object v = l1.getIfPresent(key);
        BusinessMetrics metrics = metricsProvider.getIfAvailable();

        if (v != null) {
            if (metrics != null) metrics.cacheAccess(cacheName, "L1", "hit");
            @SuppressWarnings("unchecked")
            T cast = (T) v;
            return cast;
        }
        if (metrics != null) metrics.cacheAccess(cacheName, "L1", "miss");

        T data = guard.getOrLoad(key, typeRef, redisTtlSeconds, () -> {
            T loaded = loader.get();
            if (metrics != null) {
                metrics.cacheAccess(cacheName, "L2", loaded != null ? "miss-loaded" : "miss-empty");
            }
            return loaded;
        });

        if (data != null) {
            l1.put(key, data);
            if (metrics != null) metrics.cacheAccess(cacheName, "L2", "hit-or-loaded");
        }
        return data;
    }

    /** 主动清缓存(写场景调用) */
    public void evict(String key) {
        l1.invalidate(key);
    }
}
