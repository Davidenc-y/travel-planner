package com.travel.planning.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * JWT 工具类
 *
 * <p>适配 interview-system JwtUtil，改动：</p>
 * <ul>
 *   <li>包名 com.interview.gateway.security → com.travel.planning.util</li>
 *   <li>配置前缀 interview.security.jwt → jwt</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:travel-planner-secret-key-2026-must-be-long-enough-32chars}")
    private String secret;

    @Value("${jwt.expiration:86400000}")
    private long expiration;

    @Value("${jwt.refresh-expiration:604800000}")
    private long refreshExpiration;

    public String generateAccessToken(Long userId, String username) {
        return generateToken(userId, username, expiration, "access");
    }

    public String generateRefreshToken(Long userId, String username) {
        return generateToken(userId, username, refreshExpiration, "refresh");
    }

    public Long getUserIdFromToken(String token) {
        return parseToken(token).get("userId", Long.class);
    }

    public String getUsernameFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String generateToken(Long userId, String username, long exp, String type) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("type", type);
        return Jwts.builder()
                .claims(claims)
                .subject(username)
                // F84：增加唯一 jti，保证同一秒内多次签发（登录/刷新）的 token 互不相同，
                // 满足 TC-02c "刷新返回新 accessToken" 断言，并提升 token 唯一性与可撤销粒度。
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + exp))
                .signWith(key)
                .compact();
    }

    private Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
