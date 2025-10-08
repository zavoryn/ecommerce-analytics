package com.ecom.analytics.collector.service;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IdempotentService 集成测试 - 验证 Redis SETNX 幂等(三层防护中的 Layer 1)。
 *
 * 用 Testcontainers 真实启动 Redis 容器, 不 mock, 不依赖 docker-compose。
 * 跑测试前需本地 Docker daemon 运行 (CI 上 GitHub Actions 默认带 Docker)。
 *
 * 运行: mvn -pl ecom-analytics-collector test -Dtest=IdempotentServiceIT
 */
@Testcontainers
class IdempotentServiceIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    private static IdempotentService idempotentService;

    @BeforeAll
    static void setup() {
        RedisStandaloneConfiguration cfg = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        idempotentService = new IdempotentService(new StringRedisTemplate(factory));
    }

    @Test
    void firstRequestAcquired_secondBlocked() {
        String reqId = "req-" + System.nanoTime();
        assertThat(idempotentService.tryAcquire(reqId)).isTrue();
        // 同一 reqId 第二次进来必须返回 false
        assertThat(idempotentService.tryAcquire(reqId)).isFalse();
    }

    @Test
    void differentRequestIdsAllPass() {
        long base = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(idempotentService.tryAcquire("uniq-" + base + "-" + i)).isTrue();
        }
    }

    @Test
    void nullOrBlankRequestIdAllowed() {
        // 业务约定: 没传 requestId 时直接放行(由下层 DB 唯一键兜底)
        assertThat(idempotentService.tryAcquire(null)).isTrue();
        assertThat(idempotentService.tryAcquire("")).isTrue();
    }
}
