package com.travel.planning.service;

import com.travel.common.config.GrayReleaseManager;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * HC-3：行程详情读缓存（key = travel:itn:detail:{id}，DTO JSON，TTL 10min）。
 *
 * <p>读路径缓存命中且归属匹配时直接返回（跳过 DB 查询与 DTO 组装）；未命中按原逻辑
 * 组装并回写。写点主动失效挂在 ItineraryController 四个写端点（重命名/约束 PATCH/
 * 版本切换/rollback）；聊天 REFINE 写库（ItineraryVersionPortImpl）不在本步白名单，
 * 由 10min TTL 自然过期兜底（最大陈旧窗口 10 分钟）。
 * 开关 {@code perf.itinerary-cache.enabled}（默认 true）；灰度第二道门
 * {@code gray.itinerary-detail-cache.enabled}（D-2a，yml 显式 true=现状行为，false=get/put
 * 双点旁路直查）；停用或 Redis 异常一律降级为
 * 原库读路径，缓存通道不阻断主流程。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class ItineraryDetailCache {

    /** 缓存键前缀（拼 itinerary id） */
    public static final String KEY_PREFIX = "travel:itn:detail:";
    /** D-2a 灰度键（与 GrayReleaseManager.KNOWN_KEYS 登记字面一致） */
    static final String GRAY_KEY = "gray.itinerary-detail-cache.enabled";
    /** TTL：10 分钟 */
    private static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;

    /** D-2a 灰度第二道门（enabled() 键缺失回落 false，故 yml 须显式 true） */
    private final GrayReleaseManager gray;

    /** 开关（默认 true；Spring 环境由 perf.itinerary-cache.enabled 覆盖，未配置时注入 ":true" 缺省） */
    @Value("${perf.itinerary-cache.enabled:true}")
    private boolean enabled = true;

    public ItineraryDetailCache(StringRedisTemplate redisTemplate, GrayReleaseManager gray) {
        this.redisTemplate = redisTemplate;
        this.gray = gray;
    }

    /** 缓存读取：未命中/停用/异常返回 null（调用方降级为原库读路径） */
    public CachedDetail get(Long id) {
        if (!gray.enabled(GRAY_KEY)) {
            return null;
        }
        if (!enabled) {
            return null;
        }
        try {
            String json = redisTemplate.opsForValue().get(KEY_PREFIX + id);
            if (json == null) {
                return null;
            }
            Envelope envelope = JsonUtils.fromJson(json, Envelope.class);
            if (envelope == null || envelope.dto == null) {
                return null;
            }
            log.debug("[ItineraryDetailCache] 命中: id={}", id);
            return new CachedDetail(envelope.ownerId, envelope.dto);
        } catch (Exception e) {
            log.warn("[ItineraryDetailCache] 读取失败（降级原库读）: id={}, error={}", id, e.getMessage());
            return null;
        }
    }

    /** 缓存回写（DTO JSON + ownerId 封装，TTL 10min；失败仅 WARN 不阻断主路径） */
    public void put(Long id, ItineraryResponseDTO dto, Long ownerId) {
        if (!gray.enabled(GRAY_KEY)) {
            return;
        }
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + id,
                    JsonUtils.toJson(new Envelope(ownerId, dto)), TTL);
        } catch (Exception e) {
            log.warn("[ItineraryDetailCache] 回写失败（不阻断主路径）: id={}, error={}", id, e.getMessage());
        }
    }

    /** 主动失效（写点调用：重命名/约束 PATCH/版本切换/rollback；失败回落 TTL 自然过期） */
    public void evict(Long id) {
        if (!enabled) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + id);
            log.info("[ItineraryDetailCache] 已失效: id={}", id);
        } catch (Exception e) {
            log.warn("[ItineraryDetailCache] 失效失败（TTL 自然过期兜底）: id={}, error={}", id, e.getMessage());
        }
    }

    /** 缓存命中结果（ownerId 供命中侧归属快速核对；dto 为缓存体） */
    public record CachedDetail(Long ownerId, ItineraryResponseDTO dto) {
    }

    /** JSON 封装（public 字段供 Jackson；无 final 以保持可反序列化） */
    private static class Envelope {
        public Long ownerId;
        public ItineraryResponseDTO dto;

        public Envelope() {
        }

        Envelope(Long ownerId, ItineraryResponseDTO dto) {
            this.ownerId = ownerId;
            this.dto = dto;
        }
    }
}
