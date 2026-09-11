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
public class ChatPreferenceStep implements ChatPipelineStep {

    /** B3.2：步骤顺序——M3-12 步骤 3「偏好」（依据 R7-pipeline-mapping 现发送链步骤序 3，ChatService saveIfPreference :471）。
     * saveStructuredTags 为条件触发（M25，:520），不参与定序。 */
    static final int STEP_ORDER = 3;

    @Override
    public int order() {
        return STEP_ORDER;
    }

    private final PreferenceSaveService preferenceSaveService;

    /**
     * 偏好陈述消息 → 确定性保存到画像；非偏好消息/无有效偏好由服务内部静默返回。
     */
    public void saveIfPreference(Long userId, String message) {
        preferenceSaveService.saveIfPreferenceStatement(userId, message);
    }

    /**
     * M25（E4 收尾）："记住为长期偏好"——本轮结构化偏好标签直映射画像
     * （委托 {@link PreferenceSaveService#saveStructuredTags}；调用方决定是否调用）。
     */
    public void saveStructuredTags(Long userId, com.travel.common.dto.PreferenceTagsDTO tags) {
        preferenceSaveService.saveStructuredTags(userId, tags);
    }
}
