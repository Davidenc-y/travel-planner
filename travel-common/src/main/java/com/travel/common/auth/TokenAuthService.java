package com.travel.common.auth;

/**
 * TokenAuthService 公共契约（AD-2d 接口化）。
 *
 * <p>实现见 {@link TokenAuthServiceImpl}（@Component 在 Impl，双构造器留守）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface TokenAuthService {

    boolean validateAccessToken(String token);

    boolean validateRefreshToken(String token);

    String getTokenType(String token);

    String getJti(String token);

    long getRemainingMillis(String token);

    String generateAccessToken(Long userId, String username);

    String generateRefreshToken(Long userId, String username);

    Long getUserIdFromToken(String token);

    String getUsernameFromToken(String token);

    boolean validateToken(String token);
}
