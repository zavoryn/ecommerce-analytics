package com.ecom.analytics.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具(签发 / 校验)。
 *
 * Token Claim 约定:
 *   sub  - userId(字符串)
 *   iat  - 签发时间
 *   exp  - 过期时间
 *
 * 不在 JWT 里塞业务字段(角色 / 权限), 那些走会话 Redis,
 * 因为 JWT 一旦签发就无法吊销, 业务字段在线变更不便。
 */
public final class JwtUtil {

    private JwtUtil() {}

    public static String issue(long userId, String secret, long ttlSeconds) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + ttlSeconds * 1000))
                .signWith(buildKey(secret))
                .compact();
    }

    /**
     * 校验 token 并返回 Claims; 失败抛 JwtException(签名错 / 过期 / 格式错都涵盖在内)。
     */
    public static Claims parse(String token, String secret) {
        return Jwts.parser()
                .verifyWith(buildKey(secret))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public static long userIdOf(Claims c) {
        return Long.parseLong(c.getSubject());
    }

    private static SecretKey buildKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes (256-bit) for HS256");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
