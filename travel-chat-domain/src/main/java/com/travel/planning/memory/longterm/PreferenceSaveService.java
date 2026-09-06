package com.travel.planning.memory.longterm;

import com.travel.common.util.JsonUtils;
import com.travel.planning.config.LlmGovernor;
import com.travel.planning.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 确定性偏好保存（F71）。
 *
 * <p>偏好陈述消息（"记住我喜欢爬山，预算8000元"）此前依赖 Agent 自觉调用
 * save_user_profile 工具，实测 LLM 可能跳过（概率性），导致用户明确表达的偏好未落库。
 * 本服务在 ChatService 入口对这类消息做**确定性保存**：轻量模型抽取 → 直接走
 * ProfilePort.update（F70 合并去重 + "null" 归一语义），工具保留为补充通道。</p>
 */
@Slf4j
@Service
public class PreferenceSaveService {

    private final ChatModel lightModel;
    private final ProfilePort profilePort;
    // F75/B3-5：LLM 调用统一治理（偏好抽取纳入并发许可，超限降级跳过保存）
    private final LlmGovernor llmGovernor;
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;
    // M18-1：词表单源
    private final com.travel.planning.config.ChatWordLists wordLists;

    public PreferenceSaveService(@Qualifier("lightModel") ChatModel lightModel,
                                 ProfilePort profilePort,
                                 LlmGovernor llmGovernor,
                                 PromptTemplates promptTemplates,
                                 com.travel.planning.config.ChatWordLists wordLists) {
        this.lightModel = lightModel;
        this.profilePort = profilePort;
        this.llmGovernor = llmGovernor;
        this.promptTemplates = promptTemplates;
        this.wordLists = wordLists;
    }

    /**
     * 偏好陈述消息 → 轻量抽取 → 确定性保存；非偏好消息或无有效偏好时直接返回（不阻断）。
     */
    /**
     * M25（E4 收尾）："记住为长期偏好"——结构化标签直映射画像合并语义
     * （destination/interests/budget；party 不映射 travelStyle；null 不覆盖）。
     * 确定性，零 LLM。
     */
    public void saveStructuredTags(Long userId, com.travel.common.dto.PreferenceTagsDTO tags) {
        if (userId == null || userId <= 0 || tags == null) {
            return;
        }
        profilePort.update(userId,
                tags.getDestination(),
                (tags.getInterests() == null || tags.getInterests().isEmpty())
                        ? null : String.join("、", tags.getInterests()),
                tags.getBudget() == null ? null : tags.getBudget().toPlainString(),
                null);
    }

    public void saveIfPreferenceStatement(Long userId, String message) {
        if (userId == null || userId <= 0 || !isPreferenceStatement(message)) {
            return;
        }
        try {
            Map<String, Object> pref = extract(message);
            List<String> interests = toList(pref.get("interests"));
            String budgetRange = toBudget(pref.get("budgetRange"));
            String travelStyle = toStyle(pref.get("travelStyle"));
            String consumeLevel = toConsumeLevel(pref.get("consumeLevel"));
            if ((interests == null || interests.isEmpty()) && budgetRange == null
                    && travelStyle == null && consumeLevel == null) {
                return; // 无有效偏好可保存
            }
            profilePort.update(userId, null,
                    interests != null ? JsonUtils.toJson(interests) : null,
                    budgetRange, travelStyle, consumeLevel);
            log.info("[PreferenceSave] 确定性偏好保存: userId={}, interests={}, budget={}, style={}, consumeLevel={}",
                    userId, interests, budgetRange, travelStyle, consumeLevel);
        } catch (Exception e) {
            log.warn("[PreferenceSave] 偏好抽取/保存失败（不影响主流程）: userId={}, error={}",
                    userId, e.getMessage());
        }
    }

    // M18-1：触发词单源 travel.chat.word-lists.preference.statement-keywords
    private boolean isPreferenceStatement(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return com.travel.common.util.AgentOutputUtils.containsAny(message,
                wordLists.getPreference().getStatementKeywords().toArray(new String[0]));
    }

    private Map<String, Object> extract(String message) {
        String response = llmGovernor.callWithPermit("preference-extract",
                () -> lightModel.call(promptTemplates.preferenceExtract().formatted(message)));
        String json = extractJson(response);
        Map<String, Object> map = json == null ? null : JsonUtils.fromJson(json, Map.class);
        if (map == null) {
            throw new IllegalStateException("偏好抽取返回非 JSON: " + response);
        }
        return map;
    }

    private static String extractJson(String response) {
        return com.travel.common.util.AgentOutputUtils.extractJson(response);
    }

    @SuppressWarnings("unchecked")
    private static List<String> toList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()
                        && !"null".equalsIgnoreCase(String.valueOf(o).trim())) {
                    result.add(String.valueOf(o).trim());
                }
            }
            return result;
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        return List.of(s);
    }

    private static String toBudget(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue() + "元";
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        String digits = s.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits + "元";
    }

    private static String toStyle(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim().toUpperCase();
        if ("ECONOMY".equals(s) || "COMFORT".equals(s) || "LUXURY".equals(s)) {
            return s;
        }
        return null;
    }

    /** M11-4：consumeLevel 归一（经济/穷游→ECONOMICAL，品质/舒适→COMFORT，其余合法原样）。 */
    private String toConsumeLevel(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        if (s.isBlank() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        if (com.travel.common.util.AgentOutputUtils.containsAny(s,
                wordLists.getPreference().getStyleEconomy().toArray(new String[0]))) {
            return "ECONOMICAL";
        }
        if (com.travel.common.util.AgentOutputUtils.containsAny(s,
                wordLists.getPreference().getStyleComfort().toArray(new String[0]))) {
            return "COMFORT";
        }
        String upper = s.toUpperCase();
        return "ECONOMICAL".equals(upper) || "STANDARD".equals(upper)
                || "COMFORT".equals(upper) ? upper : null;
    }
}
