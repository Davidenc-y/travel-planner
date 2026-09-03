package com.travel.knowledge.rag.websearch;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * M8-4：web 搜索结果结构化抽取（lightModel + 确定性校验）。
 *
 * <p>复用 QueryUnderstanding 的「LLM 抽取 + 确定性校验」模式：</p>
 * <ul>
 *   <li>搜索<b>原文不进 prompt</b>（只进抽取器输入），抽取结果过高危词检测
 *       （PromptInjectionRule 同款语义，本地轻量实现）；</li>
 *   <li>openHours 必须匹配可解析格式、ticketPrice 必须为非负数——校验失败整体丢弃；
 *       失败全部静默降级为 null（维持 Phase 1 null 语义，不阻塞主流程）。</li>
 * </ul>
 */
@Slf4j
public class WebEnrichExtractor {

    /** 高危提示注入词（与 chat-domain PromptInjectionRule 同款语义的本地轻量实现） */
    private static final List<String> HIGH_RISK = List.of(
            "忽略", "忽略以上", "系统提示", "系统指令", "请遵循", "不要遵守",
            "重置对话", "扮演", "你是", "无视上");

    /** 开放时间宽松校验（与 OpenHoursParser 支持格式同族：全天/时间区间） */
    private static final Pattern OPEN_HOURS =
            Pattern.compile("^(全天|([01]?\\d|2[0-3]):[0-5]\\d\\s*[-—~至到]\\s*([01]?\\d|2[0-3]):[0-5]\\d)$");

    /** 抽取结果（校验通过才返回） */
    public record EnrichedFields(String openHours, Double ticketPrice) {
    }

    /**
     * 从搜索结果抽取开放时间/门票价格；lightModel 未注入或校验失败返回 empty。
     */
    public Optional<EnrichedFields> extract(ChatModel lightModel,
                                            String name, String city,
                                            WebSearchPort.WebSearchResult result) {
        if (lightModel == null || result == null) {
            return Optional.empty();
        }
        String text = (result.snippet() == null ? "" : result.snippet())
                + (result.title() == null ? "" : " " + result.title());
        if (text.isBlank()) {
            return Optional.empty();
        }
        try {
            String prompt = """
                    你是旅游信息抽取器。从搜索结果中抽取景点的开放时间和门票价格，只输出 JSON。
                    景点：%s（%s）
                    搜索文本：%s
                    输出格式：{"openHours": "格式如 09:00-17:00 或 全天，未提及填 null", "ticketPrice": 数字元，未提及填 null}
                    约束：只从搜索文本提取，不得编造；无法确定填 null。
                    """.formatted(name, city == null ? "" : city, text);
            String response = lightModel.call(prompt);
            JsonNode node = readJson(response);
            if (node == null) {
                return Optional.empty();
            }
            String openHours = node.hasNonNull("openHours") ? node.path("openHours").asText("") : "";
            Double ticketPrice = node.path("ticketPrice").isNumber()
                    ? node.path("ticketPrice").asDouble() : null;
            EnrichedFields fields = new EnrichedFields(
                    openHours.isBlank() ? null : openHours.trim(),
                    ticketPrice);
            if (!validate(fields)) {
                log.warn("[WebEnrich] 抽取校验失败，丢弃: name={}, fields={}", name, fields);
                return Optional.empty();
            }
            return Optional.of(fields);
        } catch (Exception e) {
            log.warn("[WebEnrich] 抽取失败，静默降级: name={}, err={}", name, e.getMessage());
            return Optional.empty();
        }
    }

    private static JsonNode readJson(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(response.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean validate(EnrichedFields fields) {
        if (fields.openHours() != null
                && (!OPEN_HOURS.matcher(fields.openHours()).matches()
                        || containsHighRisk(fields.openHours()))) {
            return false;
        }
        if (fields.ticketPrice() != null
                && (fields.ticketPrice() < 0 || fields.ticketPrice() > 10000
                        || Double.isNaN(fields.ticketPrice()))) {
            return false;
        }
        return true;
    }

    private static boolean containsHighRisk(String value) {
        String v = value == null ? "" : value;
        return HIGH_RISK.stream().anyMatch(v::contains);
    }
}
