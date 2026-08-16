package com.travel.planning.memory.shortterm;

import com.travel.common.entity.ChatMessage;
import com.travel.common.util.JsonUtils;
import com.travel.planning.config.LlmGovernor;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 短期会话记忆实现（F50/Phase A + F55/B1 + F57 全量汇总 + F58/B1.2 滚动/校验/硬约束）。
 *
 * <p>读取 t_chat_message（只读不改原文）；摘要存 Redis：
 * {@code session:{id}:summary}（文本）+ {@code session:{id}:summary:meta}（游标/版本）。
 * 滚动压缩 = 旧摘要 + 游标后新增消息；输出做硬约束（二次压缩/截断）与语义保真校验。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionMemoryServiceImpl implements SessionMemoryPort {

    // F67/B3-1：消息读取收口到 SessionStorePort（不直连 Mapper）
    private final SessionStorePort sessionStorePort;
    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;
    private final ShortTermMemoryProperties props;
    // F75/B3-5：LLM 调用统一治理（后台摘要纳入并发许可）
    private final LlmGovernor llmGovernor;

    @Override
    public String composeHistoryContext(String sessionId, int maxTurns) {
        return buildLines(sessionId, maxTurns * 2, true, props.getHistoryMaxTokens());
    }

    @Override
    public String getSummaryOrEmpty(String sessionId) {
        return getSummaryInfo(sessionId).text();
    }

    @Override
    public SummaryInfo getSummaryInfo(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SummaryInfo("", null, 0);
        }
        String text = redisTemplate.opsForValue().get(summaryKey(sessionId));
        String metaJson = redisTemplate.opsForValue().get(summaryMetaKey(sessionId));
        Long lastMessageId = null;
        int version = 0;
        if (metaJson != null) {
            try {
                Map<?, ?> meta = JsonUtils.fromJson(metaJson, Map.class);
                if (meta != null) {
                    Object id = meta.get("lastMessageId");
                    if (id instanceof Number n) {
                        lastMessageId = n.longValue();
                    }
                    Object v = meta.get("version");
                    if (v instanceof Number nv) {
                        version = nv.intValue();
                    }
                }
            } catch (Exception e) {
                log.warn("[SessionMemory] 摘要 meta 解析失败: sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
        return new SummaryInfo(text == null ? "" : text, lastMessageId, version);
    }

    @Override
    public void summarizeAsync(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // F75/B3-5：摘要生成纳入统一后台 LLM 治理，并发上限内执行，超限降级跳过
        llmGovernor.runBackground("session-summary", () -> doSummarize(sessionId));
    }

    @Override
    public String composeRecentWindow(String sessionId, int turns) {
        return buildLines(sessionId, Math.max(1, turns) * 2, false, Integer.MAX_VALUE);
    }

    @Override
    public int countUserTurns(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        List<ChatMessage> messages = sessionStorePort.listMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int turns = 0;
        for (ChatMessage m : messages) {
            if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                turns++;
            }
        }
        return turns;
    }

    @Override
    public int totalHistoryTokens(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        List<ChatMessage> messages = sessionStorePort.listMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimateTokens(m.getContent()) + 4;
        }
        return total;
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int han = 0;
        int other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                han++;
            } else {
                other++;
            }
        }
        return Math.max(1, (int) Math.ceil(han + other / 4.0));
    }

    @Override
    public String truncateByTokens(String text, int maxTokens) {
        if (text == null || text.isBlank() || estimateTokens(text) <= maxTokens) {
            return text;
        }
        int budget = Math.max(16, maxTokens - 8);
        double cost = 0;
        int idx = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cost += Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ? 1.0 : 0.25;
            if (cost > budget) {
                break;
            }
            idx = i + 1;
        }
        String cut = text.substring(0, Math.max(idx, Math.min(32, text.length())));
        return cut + "\n…（已截断）";
    }

    // ==================== 内部 ====================

    private void doSummarize(String sessionId) {
        try {
            List<ChatMessage> messages = sessionStorePort.listMessages(sessionId);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            SummaryInfo info = getSummaryInfo(sessionId);
            List<ChatMessage> newMessages = new ArrayList<>();
            for (ChatMessage m : messages) {
                if (info.lastMessageId() == null || m.getId() > info.lastMessageId()) {
                    newMessages.add(m);
                }
            }
            // F58/B1.2：滚动刷新门控——已有摘要且新增 user 轮数 < refreshTurns 时仅续期。
            if (info.lastMessageId() != null) {
                int newUserTurns = 0;
                for (ChatMessage m : newMessages) {
                    if (m.getRole() != null && "user".equalsIgnoreCase(m.getRole())) {
                        newUserTurns++;
                    }
                }
                if (newUserTurns < props.getSummaryRefreshTurns()) {
                    refreshTtl(sessionId);
                    return;
                }
            }
            String incremental = buildFullText(newMessages, props.getSummaryMaxChars());
            if (incremental.isBlank()) {
                if (!info.isEmpty()) {
                    refreshTtl(sessionId); // 无新增则仅续期
                }
                return;
            }

            String input;
            if (info.isEmpty()) {
                input = incremental;
            } else {
                input = "【旧摘要】\n" + info.text() + "\n\n【新增对话】\n" + incremental;
            }

            String summary = callSummarize(input, props.getSummaryHardMaxTokens());
            if (summary.isBlank()) {
                log.warn("[SessionMemory] 摘要生成为空，跳过保存: sessionId={}", sessionId);
                return;
            }

            // 语义保真校验 + 重试
            if (props.isSummaryValidate()) {
                List<String> missing = validateSummary(incremental, summary);
                int retry = 0;
                while (!missing.isEmpty() && retry < props.getSummaryRetryTimes()) {
                    summary = callSummarize(
                            input + "\n\n【必须补充】" + String.join("、", missing),
                            props.getSummaryHardMaxTokens());
                    missing = validateSummary(incremental, summary);
                    retry++;
                }
                if (!missing.isEmpty()) {
                    log.warn("[SessionMemory] 摘要保真校验未通过，降级不保存: sessionId={}, missing={}",
                            sessionId, missing);
                    return; // 调用方本轮回退原文窗口
                }
            }

            // 输出硬约束：超限二次压缩 → 仍超则截断
            summary = enforceHardLimit(summary);

            Long newestId = messages.get(messages.size() - 1).getId();
            saveSummary(sessionId, summary, newestId, info.version() + 1);
            log.info("[SessionMemory] 会话摘要已保存: sessionId={}, version={}, lastMessageId={}, 长度={}",
                    sessionId, info.version() + 1, newestId, summary.length());
        } catch (Exception e) {
            log.warn("[SessionMemory] 摘要生成失败（降级为原文窗口）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    private String callSummarize(String input, int maxTokens) {
        String prompt = String.format("""
                你是会话摘要器。把以下对话压缩为一段中文摘要，必须保留：
                目的地、天数、预算、兴趣偏好、出行人员、已确认计划、待办事项。
                检索引用类内容可选择性保留或概括，其原始数据由知识库/会话知识承载，不必逐条保留。
                不要输出任何前缀、标题或解释，只输出摘要文本（不超过 %d tokens）。

                对话：
                %s
                """, maxTokens, input);
        String result = chatModel.call(prompt);
        return result == null ? "" : result.trim();
    }

    /**
     * 语义保真校验：对比原对话与摘要，返回缺失的关键点列表（空=通过）
     */
    @SuppressWarnings("unchecked")
    private List<String> validateSummary(String original, String summary) {
        try {
            String prompt = String.format("""
                    对比"原对话"与"摘要"，判断摘要是否遗漏关键旅游信息
                    （目的地、天数、预算、兴趣、出行人员、已确认计划、待办）。
                    注意：检索引用类内容允许省略或概括（其来源可为知识库/会话知识参考），
                    仅当上述关键信息缺失时才算遗漏。
                    只输出 JSON：{"ok": true或false, "missing": ["遗漏点1", ...]}

                    原对话：
                    %s

                    摘要：
                    %s
                    """, original, summary);
            String response = chatModel.call(prompt);
            String json = extractJson(response);
            if (json == null) {
                return List.of(); // 解析失败视为通过，避免误降级
            }
            Map<String, Object> map = JsonUtils.fromJson(json, Map.class);
            if (map == null || Boolean.TRUE.equals(map.get("ok"))) {
                return List.of();
            }
            Object missing = map.get("missing");
            if (missing instanceof List<?> list && !list.isEmpty()) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        } catch (Exception e) {
            log.debug("[SessionMemory] 保真校验解析失败，视为通过: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        return (start >= 0 && end > start) ? response.substring(start, end + 1) : null;
    }

    /**
     * 输出硬约束：token 超限 → 二次压缩 → 仍超 → 语义截断
     */
    private String enforceHardLimit(String summary) {
        int hard = props.getSummaryHardMaxTokens();
        if (estimateTokens(summary) <= hard) {
            return summary;
        }
        String recompressed = callSummarize(
                "把以下摘要压缩到不超过 " + hard + " tokens，只保留最关键信息：\n" + summary, hard);
        if (recompressed.isBlank() || estimateTokens(recompressed) > hard) {
            String truncated = truncateByTokens(
                    recompressed.isBlank() ? summary : recompressed, hard);
            log.warn("[SessionMemory] 摘要超限，已二次压缩/截断: tokens={}",
                    estimateTokens(truncated));
            return truncated;
        }
        return recompressed;
    }

    private void saveSummary(String sessionId, String text, Long lastMessageId, int version) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lastMessageId", lastMessageId);
        meta.put("version", version);
        redisTemplate.opsForValue().set(summaryKey(sessionId), text,
                props.getSummaryTtlDays(), TimeUnit.DAYS);
        redisTemplate.opsForValue().set(summaryMetaKey(sessionId), JsonUtils.toJson(meta),
                props.getSummaryTtlDays(), TimeUnit.DAYS);
    }

    private void refreshTtl(String sessionId) {
        String text = getSummaryOrEmpty(sessionId);
        if (!text.isBlank()) {
            redisTemplate.expire(summaryKey(sessionId), props.getSummaryTtlDays(), TimeUnit.DAYS);
            redisTemplate.expire(summaryMetaKey(sessionId), props.getSummaryTtlDays(), TimeUnit.DAYS);
        }
    }

    private String summaryKey(String sessionId) {
        return "session:" + sessionId + ":summary";
    }

    private String summaryMetaKey(String sessionId) {
        return "session:" + sessionId + ":summary:meta";
    }

    private String buildLines(String sessionId, int maxLines, boolean withHeader, int maxTokens) {
        if (sessionId == null || sessionId.isBlank() || maxLines <= 0) {
            return "";
        }
        List<ChatMessage> messages = sessionStorePort.listMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        int tokens = 0;
        for (int i = messages.size() - 1; i >= 0 && lines.size() < maxLines; i--) {
            ChatMessage m = messages.get(i);
            String content = m.getContent() == null ? "" : m.getContent();
            int t = estimateTokens(content) + 4;
            if (!lines.isEmpty() && tokens + t > maxTokens) {
                break;
            }
            lines.add((m.getRole() != null ? m.getRole() : "user") + ": " + content);
            tokens += t;
        }
        Collections.reverse(lines);
        if (lines.isEmpty()) {
            return "";
        }
        String body = String.join("\n", lines);
        return withHeader ? "【历史对话】\n" + body : body;
    }

    private String buildFullText(List<ChatMessage> messages, int maxChars) {
        if (messages == null || messages.isEmpty() || maxChars <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : messages) {
            String content = m.getContent() == null ? "" : m.getContent();
            if (sb.length() + content.length() > maxChars) {
                break;
            }
            sb.append(m.getRole() != null ? m.getRole() : "user").append(": ").append(content).append("\n");
        }
        return sb.toString();
    }
}
