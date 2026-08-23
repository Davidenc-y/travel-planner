package com.travel.planning.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M3-20：Prompt 模板外置加载器（P1-17）。
 *
 * <p>从 classpath {@code prompts/<name>.st} 加载并缓存；内容原样返回
 * （含末尾换行，与 Java 文本块语义一致），含占位符的模板由调用方
 * {@code .formatted(...)} 填充。模板清单与版本见 {@code prompts/README.md}。</p>
 */
@Component
public class PromptTemplates {

    private static final String DIR = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** 按模板名加载（prompts/&lt;name&gt;.st）；缺失即快速失败。 */
    public String load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private String read(String name) {
        try {
            ClassPathResource resource = new ClassPathResource(DIR + name + ".st");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Prompt 模板缺失: prompts/" + name + ".st", e);
        }
    }

    // ---- Supervisor / 直答 / 回顾 ----

    public String supervisorSystem() {
        return load("supervisor_system");
    }

    public String directRecallSystem() {
        return load("direct_recall_system");
    }

    public String directAnswerSystem() {
        return load("direct_answer_system");
    }

    public String recallSystem() {
        return load("recall_system");
    }

    // ---- 子 Agent（M3-7 AbstractReactSubAgent） ----

    public String agentBudgetSystem() {
        return load("agent_budget_system");
    }

    public String agentBudgetInstruction() {
        return load("agent_budget_instruction");
    }

    public String agentPreferenceSystem() {
        return load("agent_preference_system");
    }

    public String agentPreferenceInstruction() {
        return load("agent_preference_instruction");
    }

    public String agentRouteSystem() {
        return load("agent_route_system");
    }

    public String agentRouteInstruction() {
        return load("agent_route_instruction");
    }

    public String agentAttractionSystem() {
        return load("agent_attraction_system");
    }

    public String agentAttractionInstruction() {
        return load("agent_attraction_instruction");
    }

    // ---- 抽取 / 分类 / 输入组装 ----

    public String preferenceExtract() {
        return load("preference_extract");
    }

    public String intentClassify() {
        return load("intent_classify");
    }

    public String itineraryUserInput() {
        return load("itinerary_user_input");
    }

    public String profileHistoryCompact() {
        return load("profile_history_compact");
    }

    public String sessionSummary() {
        return load("session_summary");
    }

    public String sessionSummaryValidate() {
        return load("session_summary_validate");
    }

    // ---- M4-5a：在线相关性 Judge ----

    public String ragJudgeRelevance() {
        return load("rag_judge_relevance");
    }
}
