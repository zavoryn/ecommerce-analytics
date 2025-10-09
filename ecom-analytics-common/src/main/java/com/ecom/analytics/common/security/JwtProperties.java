package com.ecom.analytics.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置(共享给 gateway 校验 / collector 签发)。
 *
 * 配置示例(application.yml):
 *   ecom:
 *     jwt:
 *       secret: ${JWT_SECRET:dev-secret-please-change-in-prod-min-32-bytes-long}
 *       ttl-seconds: 7200
 *
 * 生产强制要求:
 *   - secret 至少 32 字节(HS256 要求 256 bit)
 *   - 必须通过环境变量 / 配置中心注入, 不允许出现在代码或 yml 中
 */
@ConfigurationProperties(prefix = "ecom.jwt")
public class JwtProperties {

    /** HMAC-SHA256 签名密钥, 至少 32 字节 */
    private String secret = "dev-secret-please-change-in-prod-min-32-bytes-long";

    /** Token 有效期(秒) */
    private long ttlSeconds = 7200;

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }
}
