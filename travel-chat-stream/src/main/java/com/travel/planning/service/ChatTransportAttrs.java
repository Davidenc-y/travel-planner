package com.travel.planning.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.dto.PreferenceTagsDTO;

/**
 * R5.1：流式传输请求装配单点——preferences/anchorIds 两键的 put/get 唯一定义。
 *
 * <p>MVC {@code ChatController} 与 WebFlux {@code ChatStreamWebfluxController}
 * 只做"HTTP 体 → ChatTransportAttrs.put → ChatStreamService"，不再各自内联
 * put/get；键名与消费侧 {@link ChatStreamService} 的读取契约一致。</p>
 *
 * <p>null 语义与现网一致（迁移自两端控制器原 if 守卫）：preferences 为 null
 * 不放入、anchorIds 为 null 或空列表不放入——不是"放入 null"。</p>
 */
public final class ChatTransportAttrs {

    public static final String KEY_PREFERENCES = "preferences";
    public static final String KEY_ANCHOR_IDS = "anchorIds";

    private ChatTransportAttrs() {
    }

    public static final void put(Map<String, Object> attrs, PreferenceTagsDTO preferences, List<Long> anchorIds) {
        if (anchorIds != null && !anchorIds.isEmpty()) {
            attrs.put(KEY_ANCHOR_IDS, anchorIds);
        }
        if (preferences != null) {
            attrs.put(KEY_PREFERENCES, preferences);
        }
    }

    public static final PreferenceTagsDTO preferences(Map<String, Object> attrs) {
        Object prefs = attrs.get(KEY_PREFERENCES);
        if (prefs instanceof PreferenceTagsDTO dto) {
            return dto;
        }
        if (prefs instanceof Map<?, ?> map) {
            try {
                return new ObjectMapper().convertValue(map, PreferenceTagsDTO.class);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public static final List<Long> anchorIds(Map<String, Object> attrs) {
        Object ids = attrs.get(KEY_ANCHOR_IDS);
        if (ids instanceof List<?> list) {
            return list.stream()
                    .filter(x -> x instanceof Number)
                    .map(x -> ((Number) x).longValue())
                    .toList();
        }
        return List.of();
    }
}
