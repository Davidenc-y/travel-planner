package com.travel.knowledge.rag.retrieval;

import com.travel.common.util.PromptFiles;
import com.travel.knowledge.rag.model.QueryIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MR-D2：LLM 多 Query 扩展器（文章 §4.3 方法四/§7.1）。
 *
 * <p>门控 {@code travel.rag.query.llm-expand.enabled}（默认 <b>false</b>，E-33）：
 * bean 仅在 enabled=true 时创建（{@code @ConditionalOnProperty}），创建后既有
 * {@code RuleBasedPlanningQueryExpander}（@ConditionalOnMissingBean）自动让位——
 * <b>键选择并存，默认 rule=现状</b>。消费方 HybridRagStrategy 以 optional 注入消费：
 * 未注入=现状单路。</p>
 *
 * <p>{@link #variantsOf}：light 生成 N 个检索变体（N=variants，默认 3，夹逼 1~5）；
 * light 失败/解析失败/空输出 fail-open 返回单变体（原 query）。多路检索与 RRFusion
 * 合并由消费方执行（复用 G11 既有融合能力）。</p>
 *
 * <p>Prompt 经 classpath {@code prompts/query_multi_expand.st}（PromptFiles；落点见
 * 方案 03 D1 修订 2026-09-18 自审裁决——knowledge 侧 prompt 单源）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "travel.rag.query.llm-expand.enabled", havingValue = "true")
public class LlmQueryExpander implements QueryExpander {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatModel lightModel;

    @Value("${travel.rag.query.llm-expand.variants:3}")
    private int variants;

    public LlmQueryExpander(@Qualifier("lightModel") ChatModel lightModel) {
        this.lightModel = lightModel;
    }

    /** 配置面（单测注入用；生产由 @Value 绑定） */
    void setVariants(int variants) {
        this.variants = variants;
    }

    /** QueryExpander SPI 兼容面：变体合并为单检索文本（供 AbstractRagStrategy 可选注入链） */
    @Override
    public String expand(QueryIntent intent) {
        List<String> vs = variantsOf(intent);
        return String.join(" ", vs);
    }

    /**
     * 多 Query 变体：light 失败/解析失败/空输出 → fail-open 单变体（原 query）。
     */
    public List<String> variantsOf(QueryIntent intent) {
        String raw = intent == null ? "" : intent.rawQuery();
        if (raw == null || raw.isBlank()) {
            return List.of(raw == null ? "" : raw);
        }
        try {
            int n = Math.max(1, Math.min(variants, 5));
            String out = lightModel.call(PromptFiles.get("query_multi_expand").formatted(n, raw, n));
            Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(out);
            if (!m.find()) {
                // 二次审批修复补观测（2026-09-18）：原实现此路径静默——提示词曾要求纯文本行而
                // 解析器期望 JSON，格式永不相交致门控开启也永远单变体（.st 已同步改 JSON 输出）
                log.info("[LlmQueryExpand] no-json in model output (len={}), single variant", out == null ? -1 : out.length());
                return List.of(raw);
            }
            JsonNode root = JSON.readTree(m.group());
            JsonNode arr = root.get("variants");
            List<String> list = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                arr.forEach(node -> {
                    String v = node.asText("");
                    if (!v.isBlank()) {
                        list.add(v.trim());
                    }
                });
            }
            if (!list.isEmpty()) {
                // 二次审批补观测（2026-09-18）：授权新前缀，门控开启后可从日志确认生效
                log.info("[LlmQueryExpand] invoked, variants={} rawLen={}", list.size(), raw.length());
            } else {
                log.info("[LlmQueryExpand] empty variants array, single variant");
            }
            return list.isEmpty() ? List.of(raw) : list;
        } catch (Exception e) {
            log.info("[LlmQueryExpand] fail-open, single variant: {}", e.getMessage());
            return List.of(raw);
        }
    }
}
