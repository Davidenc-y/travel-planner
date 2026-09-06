package com.travel.common.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M6-25：TokenAuthService 签发/解析/校验行为锁定测试。
 *
 * <p>与原 JwtUtil 行为等价：access/refresh 签发 → 解析 userId/username 一致；
 * 非法 token 校验返回 false；解析非法 token 抛异常。</p>
 */
class TokenAuthServiceTest {

    private final TokenAuthService service = new TokenAuthService(
            "travel-planner-secret-key-2026-must-be-long-enough-32chars",
            86_400_000L,
            604_800_000L);

    @Test
    void accessToken_roundTrip_returnsSameIdentity() {
        String token = service.generateAccessToken(42L, "alice");

        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getUserIdFromToken(token)).isEqualTo(42L);
        assertThat(service.getUsernameFromToken(token)).isEqualTo("alice");
    }

    @Test
    void refreshToken_roundTrip_returnsSameIdentity() {
        String token = service.generateRefreshToken(7L, "bob");

        assertThat(service.validateToken(token)).isTrue();
        assertThat(service.getUserIdFromToken(token)).isEqualTo(7L);
        assertThat(service.getUsernameFromToken(token)).isEqualTo("bob");
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertThat(service.validateToken("not-a-jwt")).isFalse();
        assertThat(service.validateToken(null)).isFalse();
    }

    @Test
    void parseToken_invalidToken_throws() {
        assertThatThrownBy(() -> service.getUserIdFromToken("not-a-jwt"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void accessAndRefreshTokens_areDistinct() {
        String access = service.generateAccessToken(1L, "alice");
        String refresh = service.generateRefreshToken(1L, "alice");

        assertThat(access).isNotEqualTo(refresh);
    }
}
