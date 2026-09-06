package com.travel.planning.memory.focus;

import com.travel.planning.config.ChatWordLists;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M24（E3）：DETOUR 词表匹配器（意图无关预判，供写通道闸门使用）。
 *
 * <p>判定（保守，双层）：① detour 词表命中；② <b>主线保护</b>——消息含
 * 规划/变更/回顾任一主线关键词时否决偏移判定（"明天天气适合穿什么"→偏移；
 * "查下天气再规划行程"→主线）。与 {@link AttentionFocusResolver} 的差异：
 * 本匹配器不依赖意图分类结果（意图在流水线步骤 5，而偏好/切片闸门在步骤 3/4），
 * 供步骤 3/4 的意图无关预判；带意图的完整判定仍在步骤 5 后的观测日志。</p>
 */
@Component
@RequiredArgsConstructor
public class DetourWordMatcher {

    private final ChatWordLists wordLists;

    /** detour 词命中且无主线关键词 → 疑似偏移（供闸门预判；意图无关）。 */
    public boolean isLikelyDetour(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        boolean hitDetour = false;
        for (String kw : wordLists.getDetour()) {
            if (kw != null && !kw.isBlank() && message.contains(kw)) {
                hitDetour = true;
                break;
            }
        }
        if (!hitDetour) {
            return false;
        }
        // 主线保护：规划/变更/回顾任一关键词命中 → 否决偏移
        ChatWordLists.Heuristics h = wordLists.getHeuristics();
        if (containsAny(message, h.getPlanning()) || containsAny(message, h.getChange())
                || containsAny(message, h.getRecall())) {
            return false;
        }
        return true;
    }

    private static boolean containsAny(String message, java.util.List<String> words) {
        if (words == null) {
            return false;
        }
        for (String w : words) {
            if (w != null && !w.isBlank() && message.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
