package com.travel.planning.memory.pipeline;

import com.travel.planning.memory.longterm.PreferenceSaveService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M3-12：MessagePipeline 步骤 3「偏好」。
 * 偏好陈述消息的确定性保存从 ChatService 抽出为独立可测步骤。
 *
 * <p>本步骤不做入口过滤（偏好判定在 {@link PreferenceSaveService} 内部，
 * 非偏好消息自然返回），保持与原调用完全等价。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatPreferenceStep {

    private final PreferenceSaveService preferenceSaveService;

    /**
     * 偏好陈述消息 → 确定性保存到画像；非偏好消息/无有效偏好由服务内部静默返回。
     */
    public void saveIfPreference(Long userId, String message) {
        preferenceSaveService.saveIfPreferenceStatement(userId, message);
    }
}
