package com.travel.planning.service;

import com.travel.common.dto.PreferenceTagsDTO;

import java.util.List;

/**
 * Z-4c：聊天轮次请求参数对象（收敛 sendMessage×4/prepareStream×3 伸缩重载）。
 *
 * <p>字段面=prepareStream 七参主方法的实参全集（sendMessage 六参面=preferences 恒 null
 * 的子集）；真实逻辑单点收敛至 {@code ChatService.sendMessage(ChatTurnRequest)} 与
 * {@code ChatService.prepareStream(ChatTurnRequest)}，旧签名保留 @Deprecated 委托
 * （编译兼容，公共 API 零删除）。</p>
 */
public record ChatTurnRequest(
        Long userId,
        String sessionId,
        String message,
        String clientMessageId,
        String model,
        List<Long> anchorIds,
        PreferenceTagsDTO preferences) {

    /** 无偏好请求（sendMessage 六参面；锚定空集语义由调用点显式给 List.of() 或主方法兜底）。 */
    public static ChatTurnRequest of(Long userId, String sessionId, String message,
                                     String clientMessageId, String model, List<Long> anchorIds) {
        return new ChatTurnRequest(userId, sessionId, message, clientMessageId, model, anchorIds, null);
    }
}
