package com.travel.planning.memory.focus;

import com.travel.planning.config.ChatWordLists;
import com.travel.planning.memory.chat.ChatIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * M23b（E3，P-F 第一阶段）：注意力焦点确定性判定器（零 LLM）。
 *
 * <p>三层判定（保守设计——宁可漏判为主线，不可误判主线为偏移）：
 * ① 意图层：PLANNING/REFINE/RECALL → MAINLINE；PROFILE → MAINLINE（只读主线状态）；
 * ② 结构层：CHAT/FUNCTIONAL 交由 detour 词表与句式判定；
 * ③ 词表层：{@code travel.chat.word-lists.detour}（天气/汇率/翻译等高频无关模式）命中 → DETOUR。</p>
 *
 * <p>观测模式：本轮只<b>判定 + 结构化日志</b>（[ChatFocus]），不做写通道隔离——
 * 三闸门（切片→摘要→画像）待观测期达标（D-V8-4：≥30 样本/误判率&lt;5%/严重误伤=0）后分批开启。</p>
 */
@Component
@RequiredArgsConstructor
public class AttentionFocusResolver {

    /** 焦点种类（M23b 仅 MAINLINE/DETOUR 两者；PROFILE_EDGE 预留）。 */
    public enum FocusKind { MAINLINE, PROFILE_EDGE, DETOUR }

    private final ChatWordLists wordLists;

    /** 确定性判定：意图 + detour 词表。 */
    public FocusKind resolve(ChatIntent intent, String message) {
        if (intent == ChatIntent.PLANNING || intent == ChatIntent.REFINE
                || intent == ChatIntent.RECALL || intent == ChatIntent.PROFILE) {
            return FocusKind.MAINLINE;
        }
        // CHAT / FUNCTIONAL：detour 词表命中 → 偏移
        if (message != null) {
            for (String kw : wordLists.getDetour()) {
                if (kw != null && !kw.isBlank() && message.contains(kw)) {
                    return FocusKind.DETOUR;
                }
            }
        }
        return FocusKind.MAINLINE;
    }
}
