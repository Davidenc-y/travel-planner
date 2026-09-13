package com.travel.planning.service;

import com.travel.common.exception.BusinessException;
import com.travel.planning.memory.pipeline.ChatTitleProperties;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * MI-1：会话标题策略（首条消息标题联动 + 标题生成/标题更新，方法体自 ChatService 逐字符迁出，
 * 文案与语义零变更；行内初始化保持既有直构签名不变）。
 */
@Slf4j
@Component
public class ChatTurnTitlePolicy {

    /**
     * M5-1：首条消息标题联动（短全量/长截断；仅默认标题生效，不覆盖手动标题；
     * 更新失败仅 WARN，不阻断发送主链路）。返回生效的新标题（未生效/失败为 null）。
     */
    public String firstMessageTitle(SessionStorePort sessionStorePort, ChatTitleProperties titleProps,
            String sessionId, String message) {
        String generatedTitle = buildSessionTitle(message, titleProps.getMaxLength());
        if (generatedTitle != null) {
            try {
                if (sessionStorePort.updateTitleIfDefault(
                        sessionId, generatedTitle, SessionStorePort.DEFAULT_SESSION_TITLE) > 0) {
                    return generatedTitle;
                }
            } catch (Exception e) {
                log.warn("[SessionTitle] 首条消息标题更新失败，继续发送: sessionId={}", sessionId, e);
            }
        }
        return null;
    }

    /**
     * M5-1：更新会话标题（前端双击编辑；归档会话也可改标题——只读历史仍展示）。
     * 会话归属校验由 ChatService 薄封装先行完成（requireOwnedSession 语义不变）。
     */
    public void updateTitle(SessionStorePort sessionStorePort, String sessionId, String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(40001, "会话标题不能为空");
        }
        if (normalized.length() > 200) {
            throw new BusinessException(40001, "会话标题不能超过200个字符");
        }
        int updated = sessionStorePort.updateTitle(sessionId, normalized);
        if (updated == 0) {
            throw new BusinessException(40404, "会话不存在: " + sessionId);
        }
    }

    /** M5-1：基于首条用户消息生成会话标题（不引 LLM：短全量、长截断） */
    public static String buildSessionTitle(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        String normalized = message.trim().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "…";
    }
}
