package com.travel.planning.memory.knowledge;

import com.travel.common.util.JsonUtils;
import com.travel.planning.config.ChatWordLists;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话知识切片器（Phase C/F78，C1 切片规则下沉到写入侧）。
 *
 * <p>按 F49 第九节结构化知识单元切分：自由文本按句切（。！？；换行），
 * 命中约束/反馈模式打 type；行程 JSON 按天拆子块。knowledge 侧仅规范化+向量化。</p>
 */
@Slf4j
@Component
public class SessionContextChunker {

    // M18-1：词表单源 travel.chat.word-lists.chunker（F81 收紧语义与注释见 yml/ChatWordLists）
    private final ChatWordLists wordLists;

    public SessionContextChunker(ChatWordLists wordLists) {
        this.wordLists = wordLists;
    }

    /**
     * 从用户消息中提取 constraint / feedback 切片（一句一条，语义句切分）。
     */
    public List<SessionChunk> chunkUserMessage(String sessionId, String message) {
        if (sessionId == null || sessionId.isBlank() || message == null || message.isBlank()) {
            return List.of();
        }
        List<SessionChunk> chunks = new ArrayList<>();
        for (String sentence : message.split("[。！？；!?;\n]")) {
            String s = sentence.trim();
            if (s.length() < 2) {
                continue;
            }
            if (containsAny(s, wordLists.getChunker().getFeedbackPatterns().toArray(new String[0]))) {
                chunks.add(new SessionChunk(sessionId, "feedback", null, s, "user", "chat"));
            } else if (matchesConstraint(s)) {
                chunks.add(new SessionChunk(sessionId, "constraint", null, s, "user", "chat"));
            }
        }
        return chunks;
    }

    /**
     * 从行程 JSON 提取 itinerary_day 切片（按天拆分，JSON 字段级子块）。
     *
     * @param itineraryId 行程主键（seq 前缀，保证稳定幂等）
     */
    public List<SessionChunk> chunkItinerary(String sessionId, String itineraryJson, Long itineraryId) {
        if (sessionId == null || sessionId.isBlank() || itineraryJson == null || itineraryJson.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> content = JsonUtils.fromJson(itineraryJson, Map.class);
            if (content == null || !(content.get("routePlan") instanceof Map<?, ?> routePlan)) {
                return List.of();
            }
            Object daysObj = ((Map<?, ?>) routePlan).get("days");
            if (!(daysObj instanceof List<?> days)) {
                return List.of();
            }
            List<SessionChunk> chunks = new ArrayList<>();
            for (int i = 0; i < days.size(); i++) {
                if (!(days.get(i) instanceof Map<?, ?> day)) {
                    continue;
                }
                String dayText = buildDayText(i + 1, day);
                if (dayText.isBlank()) {
                    continue;
                }
                chunks.add(new SessionChunk(sessionId, "itinerary_day",
                        "itin:" + (itineraryId == null ? "x" : itineraryId) + ":" + (i + 1),
                        dayText, "assistant", "route"));
            }
            return chunks;
        } catch (Exception e) {
            log.warn("[SessionChunker] 行程切片失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildDayText(int dayNo, Map<?, ?> day) {
        StringBuilder sb = new StringBuilder("第").append(dayNo).append("天");
        if (day.get("date") != null) {
            sb.append(" ").append(day.get("date"));
        }
        if (day.get("summary") != null) {
            sb.append(" 概要：").append(day.get("summary"));
        }
        Object attractions = day.get("attractions");
        if (attractions instanceof List<?> list && !list.isEmpty()) {
            sb.append(" 景点：");
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof Map<?, ?> a) {
                    if (i > 0) {
                        sb.append("、");
                    }
                    sb.append(a.get("name"));
                    if (a.get("cost") != null) {
                        sb.append("(").append(a.get("cost")).append("元)");
                    }
                }
            }
        }
        if (day.get("hotelSuggestion") != null) {
            sb.append(" 住宿：").append(day.get("hotelSuggestion"));
        }
        return sb.toString();
    }

    private static boolean containsAny(String text, String... tokens) {
        return com.travel.common.util.AgentOutputUtils.containsAny(text, tokens);
    }

    /** F81：约束判定 = 确认性子串 或 数字预算/天数 正则 */
    private boolean matchesConstraint(String text) {
        for (String p : wordLists.getChunker().getConstraintSubstrings()) {
            if (text.contains(p)) {
                return true;
            }
        }
        for (java.util.regex.Pattern pattern : wordLists.getChunker().getCompiledRegex()) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
