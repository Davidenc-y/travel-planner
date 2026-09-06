package com.travel.planning.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * accessToken 吊销黑名单（M21-4，SEC-01-03 止血）。
 *
 * <p>登出时把当前 access token 的 jti 写入 Redis（TTL=令牌剩余有效期），
 * {@code JwtAuthInterceptor} 对每个已解析请求先查黑名单——登出后旧 token
 * 立即失效，不再存在"泄露 token 登出后仍可用至 24h"的窗口。</p>
 *
 * <p>键空间：{@code travel:jwt:blacklist:{jti}}；值恒为 "1"。
 * Redis 不可用时 {@code isRevoked} 按"未吊销"降级（fail-open，保可用性——
 * 与登出删 refresh 的既有 Redis 依赖同级；Redis 故障本就会导致登录/刷新不可用）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessTokenBlacklistService {

    private static final String KEY_PREFIX = "travel:jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    /** 吊销指定 jti（ttlMillis<=0 或 jti 为空时忽略）。 */
    public void revoke(String jti, long ttlMillis) {
        if (jti == null || jti.isBlank() || ttlMillis <= 0) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
            log.info("[Auth] accessToken 已吊销: jti={}, ttlMs={}", jti, ttlMillis);
        } catch (Exception e) {
            log.warn("[Auth] 黑名单写入失败（登出后旧 token 可能仍可用至过期）: {}", e.getMessage());
        }
    }

    /** 是否已吊销（Redis 异常按未吊销降级，不阻断请求链）。 */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + jti));
        } catch (Exception e) {
            log.debug("[Auth] 黑名单查询降级（Redis 异常）: {}", e.getMessage());
            return false;
        }
    }
}
