package com.travel.knowledge.rag.retrieval;

import com.travel.common.util.PromptFiles;
import com.travel.knowledge.rag.model.QueryIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * MR-D1：HyDE 假设答案改写器（文章 §3.2/§4.3 方法二）。
 *
 * <p>门控 {@code travel.rag.query.hyde.enabled}（默认 <b>false=关闭</b>，E-33）：关闭时
 * {@link #vectorQueryText} 原样返回 raw query（零 LLM 调用、零路径变更）；开启时用 light
 * 模型生成"假设性文档文本"作为<b>向量路（KNN）</b>检索文本，原 query 仍保留参与 BM25
 * （双路语义，由消费方 HybridRagStrategy 保证）。light 失败/空输出 fail-open 用原 query。</p>
 *
 * <p>Prompt 经 classpath {@code prompts/hyde_rewrite.st}（PromptFiles，knowledge 侧
 * prompt 单源；见方案 03 D1 修订 2026-09-18 自审裁决）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HydeQueryRewriter {

    private final ChatModel lightModel;

    @Value("${travel.rag.query.hyde.enabled:false}")
    private boolean enabled;

    /** 配置面（单测注入用；生产由 @Value 绑定） */
    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 向量路检索文本：关闭/fail-open → 原 query；开启 → light 假设性文档文本（trim）。
     */
    public String vectorQueryText(QueryIntent intent) {
        String raw = intent == null ? "" : intent.rawQuery();
        if (!enabled || raw == null || raw.isBlank()) {
            return raw == null ? "" : raw;
        }
        try {
            String hypothetical = lightModel.call(PromptFiles.get("hyde_rewrite").formatted(raw));
            if (hypothetical == null || hypothetical.isBlank()) {
                log.info("[HydeRewrite] blank output, fallback to raw query (len={})", raw.length());
                return raw;
            }
            // 二次审批补观测（2026-09-18）：门控开启后无日志无法确认生效——授权新前缀
            log.info("[HydeRewrite] invoked, rawLen={} rewrittenLen={}", raw.length(), hypothetical.trim().length());
            return hypothetical.trim();
        } catch (Exception e) {
            log.info("[HydeRewrite] fail-open, fallback to raw query: {}", e.getMessage());
            return raw;
        }
    }
}
