package com.travel.planning.memory.longterm;

import com.travel.common.util.JsonUtils;
import com.travel.planning.config.LlmGovernor;
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

    private static final String EXTRACT_PROMPT = """
            你是旅游偏好抽取器。只输出 JSON，不要任何其他内容：
            {"interests":[...], "budgetRange":..., "travelStyle":...}
            规则：
            - interests 只能从 [文化,自然,美食,购物,亲子,休闲] 中选择；爬山/徒步归入"自然"；无则空数组
            - budgetRange 输出"数字元"字符串（如"8000元"），未提及则 null
            - travelStyle 只能是 ECONOMY/COMFORT/LUXURY 或 null
            - 未提及的字段一律 null
            用户消息：%s
            """;

    private final ChatModel lightModel;
    private final ProfilePort profilePort;
    // F75/B3-5：LLM 调用统一治理（偏好抽取纳入并发许可，超限降级跳过保存）
    private final LlmGovernor llmGovernor;

    public PreferenceSaveService(@Qualifier("lightModel") ChatModel lightModel,
                                 ProfilePort profilePort,
                                 LlmGovernor llmGovernor) {
        this.lightModel = lightModel;
        this.profilePort = profilePort;
        this.llmGovernor = llmGovernor;
    }

    /**
     * 偏好陈述消息 → 轻量抽取 → 确定性保存；非偏好消息或无有效偏好时直接返回（不阻断）。
     */
    public void saveIfPreferenceStatement(Long userId, String message) {
        if (userId == null || userId <= 0 || !isPreferenceStatement(message)) {
            return;
        }
        try {
            Map<String, Object> pref = extract(message);
            List<String> interests = toList(pref.get("interests"));
            String budgetRange = toBudget(pref.get("budgetRange"));
            String travelStyle = toStyle(pref.get("travelStyle"));
            if ((interests == null || interests.isEmpty()) && budgetRange == null && travelStyle == null) {
                return; // 无有效偏好可保存
            }
            profilePort.update(userId, null,
                    interests != null ? JsonUtils.toJson(interests) : null,
                    budgetRange, travelStyle);
            log.info("[PreferenceSave] 确定性偏好保存: userId={}, interests={}, budget={}, style={}",
                    userId, interests, budgetRange, travelStyle);
        } catch (Exception e) {
            log.warn("[PreferenceSave] 偏好抽取/保存失败（不影响主流程）: userId={}, error={}",
                    userId, e.getMessage());
        }
    }

    private static boolean isPreferenceStatement(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return message.contains("记住") || message.contains("偏好")
                || message.contains("设为") || message.contains("改为")
                || message.contains("设置为") || message.contains("预算")
                || message.contains("喜欢");
    }

    private Map<String, Object> extract(String message) {
        String response = llmGovernor.callWithPermit("preference-extract",
                () -> lightModel.call(String.format(EXTRACT_PROMPT, message)));
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
}
