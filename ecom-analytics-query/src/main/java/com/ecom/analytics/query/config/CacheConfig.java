package com.ecom.analytics.query.config;

import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * 缓存分级 TTL 配置
 *
 * Spring Cache 默认所有缓存用同一个 TTL（application.yml 中 spring.cache.redis.time-to-live）。
 * 但不同接口对实时性要求不同，必须分级配置：
 *
 * | 缓存名          | TTL   | 原因                                        |
 * |-----------------|-------|---------------------------------------------|
 * | itemTrend       | 10min | 运营实时看趋势，10min 可接受                  |
 * | funnel          | 30min | CK 漏斗计算重，30min 不会太旧                |
 * | gmvTrend        | 30min | 平台趋势折线图，30min 内变化不大              |
 * | categoryStats   | 60min | 类目分析决策型数据，T+1 聚合，60min 无问题    |
 * | topItems        | 10min | 运营选品实时性要求较高                        |
 * | hotKeywords     | 5min  | 今日实时热词，5min 内要反映搜索趋势变化       |
 * | topCategories   | 10min | 类目 GMV 排行，10min 可接受                  |
 *
 * 序列化：Value 用 GenericJackson2JsonRedisSerializer（存 JSON 可读），
 *         Key 用 StringRedisSerializer（方便 Redis CLI 查看）。
 */
@Configuration
public class CacheConfig {

    /**
     * 公共 Redis Cache 配置基础（禁止缓存 null，防止缓存穿透）
     */
    private RedisCacheConfiguration base() {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()));
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        return builder -> builder
                // 商品趋势：10min
                .withCacheConfiguration("itemTrend",
                        base().entryTtl(Duration.ofMinutes(10)))
                // 全平台漏斗：30min（CK 计算重）
                .withCacheConfiguration("funnel",
                        base().entryTtl(Duration.ofMinutes(30)))
                // 平台 GMV 趋势折线图：30min
                .withCacheConfiguration("gmvTrend",
                        base().entryTtl(Duration.ofMinutes(30)))
                // 类目统计（决策型，T+1 数据）：60min
                .withCacheConfiguration("categoryStats",
                        base().entryTtl(Duration.ofMinutes(60)))
                // TOP 商品榜（预计算，10min 刷新）
                .withCacheConfiguration("topItems",
                        base().entryTtl(Duration.ofMinutes(10)))
                // 热搜词（实时性高，5min）
                .withCacheConfiguration("hotKeywords",
                        base().entryTtl(Duration.ofMinutes(5)))
                // TOP 类目榜：10min
                .withCacheConfiguration("topCategories",
                        base().entryTtl(Duration.ofMinutes(10)));
    }
}
