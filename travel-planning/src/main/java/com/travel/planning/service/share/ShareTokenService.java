package com.travel.planning.service.share;

import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;

/**
 * M25（E5，D-V8-7）：行程分享无状态签名 token——
 * {@code {itineraryId}.{过期epochDay}.{HmacSHA256(前两段)}}，零 schema 变更。
 *
 * <p>签名密钥派生自 JWT_SECRET（SHA-256）；token 不可伪造、不可枚举；
 * 过期上限 30 天（配置 clamp）；旧 token 到期自然失效，重签即轮换。</p>
 */
@Service
@RequiredArgsConstructor
public class ShareTokenService {

    private final ItineraryMapper itineraryMapper;

    @Value("${travel.share.token-ttl-days:7}")
    private int tokenTtlDays;

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** 签发分享 token（本人行程；不存在 40401 / 非本人 40302）。 */
    public String issue(Long userId, Long itineraryId) {
        var entity = itineraryMapper.selectById(itineraryId);
        if (entity == null) {
            throw new BusinessException(40401, "行程不存在: " + itineraryId);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
        int ttl = Math.min(Math.max(tokenTtlDays, 1), 30); // clamp 1~30 天
        long expiryEpochDay = LocalDate.now().toEpochDay() + ttl;
        String payload = itineraryId + "." + expiryEpochDay;
        return payload + "." + hmac(payload);
    }

    /** 校验签名与有效期（通过 → Optional&lt;itineraryId&gt;；否则 empty）。 */
    public Optional<Long> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String payload = parts[0] + "." + parts[1];
        if (!hmac(payload).equals(parts[2])) {
            return Optional.empty(); // 篡改/伪造
        }
        long expiryEpochDay;
        long id;
        try {
            expiryEpochDay = Long.parseLong(parts[1]);
            id = Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (LocalDate.now().toEpochDay() > expiryEpochDay) {
            return Optional.empty(); // 已过期
        }
        return Optional.of(id);
    }

    private String hmac(String payload) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256")
                    .digest(jwtSecret.getBytes(StandardCharsets.UTF_8));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }
}
