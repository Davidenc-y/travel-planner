package com.travel.planning.memory.pipeline;

import com.travel.common.util.AgentOutputUtils;
import com.travel.common.util.JsonUtils;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * M4-5a：在线相关性 Judge（planning 注入侧，默认关）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>用 {@code lightModel}（qwen-turbo）<b>单次调用</b>合并判定两段注入内容
 *       （【会话知识参考】与【知识库候选景点】）与当前消息的相关性，避免两次 LLM 往返；</li>
 *   <li><b>fail-open</b>：任何异常 / 超时 / JSON 解析失败一律返回"两段均相关"（放行），
 *       保证 Judge 只做减法（剔除噪声）不做加法（丢上下文）；</li>
 *   <li>判定包在 {@link CompletableFuture#orTimeout}（虚拟线程）内，超时毫秒数由
 *       {@link RagJudgeProperties#getTimeoutMs()} 控制，超时即放行；</li>
 *   <li>prompt 模板外置 {@code prompts/rag_judge_relevance.st}（M3-20 风格，
 *       调用方 {@code .formatted(...)} 填充）；输入段做长度截断，控制轻模型时延。</li>
 * </ul>
 */
@Slf4j
@Component
public class RagJudge {

    /** 判定专用虚拟线程池（orTimeout 超时后底层调用可被丢弃，不占用平台线程） */
    private static final ExecutorService JUDGE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** 单段注入内容的截断上限（字符），控制轻模型单次调用时延 */
    private static final int SEGMENT_MAX_CHARS = 4000;

    /** fail-open 兜底结果：两段均相关（放行） */
    public static final JudgeResult FAIL_OPEN = new JudgeResult(true, true, true);

    private final ChatModel lightModel;
    private final PromptTemplates promptTemplates;
    private final RagJudgeProperties properties;

    public RagJudge(@Qualifier("lightModel") ChatModel lightModel,
                    PromptTemplates promptTemplates,
                    RagJudgeProperties properties) {
        this.lightModel = lightModel;
        this.promptTemplates = promptTemplates;
        this.properties = properties;
    }

    /**
     * 判定两段注入内容与当前消息的相关性（单次 LLM 合并判定）。
     *
     * @param message        用户当前消息
     * @param sessionContext 会话知识参考段（可为空串）
     * @param candidates     知识库候选景点段（JSON 串，可为 "[]"）
     * @return 判定结果；任何失败/超时返回 {@link #FAIL_OPEN}（fail-open 放行）
     */
    public JudgeResult judge(String message, String sessionContext, String candidates) {
        long start = System.currentTimeMillis();
        try {
            JudgeResult result = CompletableFuture
                    .supplyAsync(() -> doJudge(message, sessionContext, candidates), JUDGE_EXECUTOR)
                    .orTimeout(Math.max(1, properties.getTimeoutMs()), TimeUnit.MILLISECONDS)
                    .join();
            log.info("[RagJudge] relevant={}/{}, elapsedMs={}, fallback=false",
                    result.sessionKnowledgeRelevant(), result.attractionCandidatesRelevant(),
                    System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.warn("[RagJudge] relevant=true/true, elapsedMs={}, fallback=true（判定失败/超时，放行）: {}",
                    System.currentTimeMillis() - start, e.getMessage());
            return FAIL_OPEN;
        }
    }

    private JudgeResult doJudge(String message, String sessionContext, String candidates) {
        String prompt = promptTemplates.ragJudgeRelevance().formatted(
                truncate(message), truncate(sessionContext), truncate(candidates));
        String response = lightModel.call(prompt);
        String json = AgentOutputUtils.extractJson(response);
        JudgeRaw raw = JsonUtils.fromJson(json, JudgeRaw.class);
        if (raw == null || raw.sessionKnowledgeRelevant() == null || raw.attractionCandidatesRelevant() == null) {
            throw new IllegalStateException("Judge 输出无法解析: " + response);
        }
        return new JudgeResult(raw.sessionKnowledgeRelevant(), raw.attractionCandidatesRelevant(), false);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= SEGMENT_MAX_CHARS ? text : text.substring(0, SEGMENT_MAX_CHARS) + "…";
    }

    /**
     * 判定结果：两段是否相关 + fallback 标记（true=fail-open 放行，非模型判定）。
     */
    public record JudgeResult(boolean sessionKnowledgeRelevant,
                              boolean attractionCandidatesRelevant,
                              boolean fallback) {
    }

    /** LLM JSON 反序列化中间对象（字段缺失视为解析失败走 fail-open） */
    private record JudgeRaw(Boolean sessionKnowledgeRelevant, Boolean attractionCandidatesRelevant) {
    }
}
