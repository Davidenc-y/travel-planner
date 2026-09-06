package com.travel.common.auth;

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
 * 认证令牌服务（M6-25：JWT 签发/解析中立化）。
 *
 * <p>自 {@code com.travel.planning.util.JwtUtil} 下沉至 travel-common：
 * MVC 侧 {@code JwtAuthInterceptor} 与未来 WebFlux 侧
 * {@code ReactiveJwtAuthFilter} 共用同一套 JWT 逻辑（M6-6-R1 §2.4 [P1]），
 * 行为与旧 JwtUtil 完全等价（含 F84 唯一 jti）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
public class TokenAuthService {

    private final String secret;
    private final long expiration;
    private final long refreshExpiration;
    /** M21-4：token type 强校验开关（默认 true；false=回退为纯验签，回滚用）。 */
    private final boolean enforceType;

    @org.springframework.beans.factory.annotation.Autowired
    public TokenAuthService(
            @Value("${jwt.secret:}")
            String secret,
            @Value("${jwt.expiration:86400000}") long expiration,
            @Value("${jwt.refresh-expiration:604800000}") long refreshExpiration,
            @Value("${travel.jwt.enforce-type:true}") boolean enforceType) {
        // M21-1（SEC-01-01/D-04-001）：受控配置不再提供可用默认密钥；
        // secret 为空即启动失败（fail-fast），拒绝"空密钥静默放行"。
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "[TokenAuthService] jwt.secret 未配置（受控配置不携带默认密钥，M21-1）："
                            + "请设置环境变量 JWT_SECRET，或在 application-local.yml 显式配置 jwt.secret（仅本地开发）");
        }
        this.secret = secret;
        this.expiration = expiration;
        this.refreshExpiration = refreshExpiration;
        this.enforceType = enforceType;
    }

    /** 兼容构造（测试/显式装配）：默认启用 type 强校验。 */
    public TokenAuthService(String secret, long expiration, long refreshExpiration) {
        this(secret, expiration, refreshExpiration, true);
    }

    /**
     * M21-4（SEC-01-02 止血）：accessToken 专用校验——验签之外还要求
     * type=access（enforceType 开关控制）；refreshToken 冒充 access 在此被拒绝。
     */
    public boolean validateAccessToken(String token) {
        return validateToken(token) && (!enforceType || "access".equals(getTokenType(token)));
    }

    /** M21-4：refreshToken 专用校验（/auth/refresh 只收 refresh）。 */
    public boolean validateRefreshToken(String token) {
        return validateToken(token) && (!enforceType || "refresh".equals(getTokenType(token)));
    }

    public String getTokenType(String token) {
        try {
            return parseToken(token).get("type", String.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** M21-4（SEC-01-03）：jti 读取（登出吊销键）。 */
    public String getJti(String token) {
        try {
            return parseToken(token).getId();
        } catch (Exception e) {
            return null;
        }
    }

    /** M21-4（SEC-01-03）：剩余有效期毫秒（吊销 TTL 上界；已过期返回 -1）。 */
    public long getRemainingMillis(String token) {
        try {
            long exp = parseToken(token).getExpiration().getTime();
            return Math.max(0L, exp - System.currentTimeMillis());
        } catch (Exception e) {
            return -1L;
        }
    }

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
