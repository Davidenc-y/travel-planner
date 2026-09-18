package com.travel.planning.memory.shortterm;

import com.travel.common.entity.ChatMessage;
import com.travel.common.util.JsonUtils;
import com.travel.memory.shortterm.SessionMemoryPort;
import com.travel.memory.shortterm.ShortTermMemoryProperties;
import com.travel.memory.shortterm.WindowComposer;
import com.travel.memory.config.LlmGovernor;
import com.travel.memory.prompt.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 摘要域协作件（MM-3.1，自 SessionMemoryServiceImpl 原样迁出）。
 *
 * <p>承载摘要读写（Redis 双 key）、滚动压缩（F58/B1.2）、收口全量重算（M4-4）、
 * 语义保真校验与输出硬约束（F58/B1.2）。方法体与迁出前逐字一致；会话消息经
 * {@link Supplier} 惰性取数——由调用方（SessionMemoryServiceImpl）提供其请求内
 * 消息快照入口，保持"异步线程各自独立加载"的原语义（后台线程上 ThreadLocal
 * 缓存为空 → 重新走 SessionStorePort 加载）。</p>
 *
 * <p>M24（E3）闸门 3：摘要输入剔除 DETOUR 轮的判定与静态过滤一并迁入本类。</p>
 */
@Slf4j
@Component
public class SummaryAssembler {

    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;
    private final ShortTermMemoryProperties props;
    // F75/B3-5：LLM 调用统一治理（后台摘要纳入并发许可）
    private final LlmGovernor llmGovernor;
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;
    // MM-3.2：token 工具单一同源点（替代 3.1 临时同源副本）
    private final WindowComposer windowComposer;

    // M24（E3）：DETOUR 摘要过滤（闸门 3）——可选依赖（测试直构时为 null=不过滤）
    @Autowired(required = false)
    private com.travel.planning.config.DetourIsolationProperties detourIsolationProperties;
    @Autowired(required = false)
    private com.travel.planning.memory.focus.DetourWordMatcher detourWordMatcher;

    public SummaryAssembler(@Qualifier("lightModel") ChatModel chatModel,
                            StringRedisTemplate redisTemplate,
                            ShortTermMemoryProperties props,
                            LlmGovernor llmGovernor,
                            PromptTemplates promptTemplates,
                            WindowComposer windowComposer) {
        this.chatModel = chatModel;
        this.redisTemplate = redisTemplate;
        this.props = props;
        this.llmGovernor = llmGovernor;
        this.promptTemplates = promptTemplates;
        this.windowComposer = windowComposer;
    }

    /**
     * M4-1a/P0-1：摘要双 key CAS 原子写入脚本（版本冲突放弃，滚动/收口共用）。
     * 语义见 resources/lua/save_summary_cas.lua。
     */
    private static final DefaultRedisScript<Long> SAVE_SUMMARY_CAS = buildCasScript();

    private static DefaultRedisScript<Long> buildCasScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/save_summary_cas.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /** M24（E3）闸门 3：摘要输入剔除 DETOUR 轮（确定性重评：用户消息命中→该轮 user+紧随 assistant 一并剔除）。 */
    static java.util.List<ChatMessage> filterDetourTurns(java.util.List<ChatMessage> messages,
                                                         java.util.function.Predicate<String> detourPredicate) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        java.util.List<ChatMessage> out = new java.util.ArrayList<>();
        boolean skipAssistant = false;
        for (ChatMessage m : messages) {
            boolean isUser = m.getRole() != null && "user".equalsIgnoreCase(m.getRole());
            if (isUser) {
                boolean detour = detourPredicate.test(m.getContent());
                skipAssistant = detour;
                if (detour) {
                    continue;
                }
            } else if (skipAssistant) {
                continue; // 剔除 detour 用户消息紧随的 assistant 回复
            }
            out.add(m);
        }
        return out;
    }

    /** M24（E3）：闸门 3 生效判定（配置/匹配器缺失 = 不过滤）。 */
    private boolean summaryFilterActive() {
        return detourIsolationProperties != null && detourWordMatcher != null
                && detourIsolationProperties.isEnabled() && detourIsolationProperties.isSummaryFilter();
    }

    public SessionMemoryPort.SummaryInfo getSummaryInfo(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return new SessionMemoryPort.SummaryInfo("", null, 0);
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
        return new SessionMemoryPort.SummaryInfo(text == null ? "" : text, lastMessageId, version);
    }

    public void summarizeAsync(String sessionId, Supplier<List<ChatMessage>> messagesSupplier) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // F75/B3-5：摘要生成纳入统一后台 LLM 治理，并发上限内执行，超限降级跳过
        llmGovernor.runBackground("session-summary", () -> doSummarize(sessionId, messagesSupplier));
    }

    /**
     * M4-4/P1-1：会话收口摘要（全量重算，绕过滚动门控；同步执行）。
     *
     * <p>与 doSummarize 的差异：输入为全量消息原文（不拼旧摘要）；无 refresh-turns
     * 门控；meta.summaryType=final；CAS 失败/生成失败返回 false 且不改动既有摘要
     * （保留旧摘要，由收口器重试）。许可治理与超时预算由 SessionFinalizer 负责。</p>
     */
    public boolean finalizeSummary(String sessionId, Supplier<List<ChatMessage>> messagesSupplier) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            List<ChatMessage> messages = messagesSupplier.get();
            if (messages == null || messages.isEmpty()) {
                return true; // 空会话：无需收口，视为成功（由调用方落空摘要标记）
            }
            SessionMemoryPort.SummaryInfo info = getSummaryInfo(sessionId);
            // M24（E3）闸门 3：收口摘要同样剔除 DETOUR 轮
            if (summaryFilterActive()) {
                messages = filterDetourTurns(messages,
                        content -> detourWordMatcher.isLikelyDetour(content));
            }
            String fullText = buildFullText(messages, props.getSummaryMaxChars());
            String summary = callSummarize(fullText, props.getSummaryHardMaxTokens());
            if (summary.isBlank()) {
                log.warn("[SessionMemory] 收口摘要生成为空: sessionId={}", sessionId);
                return false;
            }
            if (props.isSummaryValidate()) {
                List<String> missing = validateSummary(fullText, summary);
                int retry = 0;
                while (!missing.isEmpty() && retry < props.getSummaryRetryTimes()) {
                    summary = callSummarize(
                            fullText + "\n\n【必须补充】" + String.join("、", missing),
                            props.getSummaryHardMaxTokens());
                    missing = validateSummary(fullText, summary);
                    retry++;
                }
                if (!missing.isEmpty()) {
                    log.warn("[SessionMemory] 收口摘要保真校验未通过，保留旧摘要: sessionId={}, missing={}",
                            sessionId, missing);
                    return false;
                }
            }
            summary = enforceHardLimit(summary);
            Long newestId = messages.get(messages.size() - 1).getId();
            boolean saved = saveSummaryChecked(sessionId, info.version(), summary, newestId,
                    info.version() + 1, "final");
            if (saved) {
                log.info("[SessionMemory] 收口摘要已保存: sessionId={}, version={}, lastMessageId={}",
                        sessionId, info.version() + 1, newestId);
            }
            return saved;
        } catch (Exception e) {
            log.warn("[SessionMemory] 收口摘要失败（保留旧摘要）: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return false;
        }
    }

    // ==================== 内部 ====================

    private void doSummarize(String sessionId, Supplier<List<ChatMessage>> messagesSupplier) {
        try {
            List<ChatMessage> messages = messagesSupplier.get();
            if (messages == null || messages.isEmpty()) {
                return;
            }
            SessionMemoryPort.SummaryInfo info = getSummaryInfo(sessionId);
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
            // M24（E3）闸门 3：摘要输入剔除 DETOUR 轮（确定性重评；默认关=零变化）
            if (summaryFilterActive()) {
                newMessages = filterDetourTurns(newMessages,
                        content -> detourWordMatcher.isLikelyDetour(content));
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
                // M7-8：校验必须针对“实际生成输入”（旧摘要+新增对话），而不是仅新增
                // 对话——否则旧上下文保留的关键项（目的地/预算/计划等）会被误判缺失，
                // 导致滚动摘要反复“降级不保存”，历史持续膨胀且上下文陈旧
                List<String> missing = validateSummary(input, summary);
                int retry = 0;
                while (!missing.isEmpty() && retry < props.getSummaryRetryTimes()) {
                    summary = callSummarize(
                            input + "\n\n【必须补充】" + String.join("、", missing),
                            props.getSummaryHardMaxTokens());
                    missing = validateSummary(input, summary);
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
            boolean saved = saveSummaryChecked(sessionId, info.version(), summary, newestId,
                    info.version() + 1, null);
            if (saved) {
                log.info("[SessionMemory] 会话摘要已保存: sessionId={}, version={}, lastMessageId={}, 长度={}",
                        sessionId, info.version() + 1, newestId, summary.length());
            }
        } catch (Exception e) {
            log.warn("[SessionMemory] 摘要生成失败（降级为原文窗口）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    private String callSummarize(String input, int maxTokens) {
        String prompt = promptTemplates.sessionSummary().formatted(maxTokens, input);
        String result = chatModel.call(prompt);
        return result == null ? "" : result.trim();
    }

    /**
     * 语义保真校验：对比原对话与摘要，返回缺失的关键点列表（空=通过）
     */
    @SuppressWarnings("unchecked")
    private List<String> validateSummary(String original, String summary) {
        try {
            String prompt = promptTemplates.sessionSummaryValidate().formatted(original, summary);
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
        if (windowComposer.estimateTokens(summary) <= hard) {
            return summary;
        }
        String recompressed = callSummarize(
                com.travel.common.util.PromptFiles.get("session_summary_recompress")
                        .formatted(hard, summary), hard);
        if (recompressed.isBlank() || windowComposer.estimateTokens(recompressed) > hard) {
            String truncated = windowComposer.truncateByTokens(
                    recompressed.isBlank() ? summary : recompressed, hard);
            log.warn("[SessionMemory] 摘要超限，已二次压缩/截断: tokens={}",
                    windowComposer.estimateTokens(truncated));
            return truncated;
        }
        return recompressed;
    }

    /**
     * M4-1a/P0-1：带 CAS 保护的摘要写入入口（滚动/收口共用）。
     *
     * <p>casEnabled=true 走 Lua CAS（expectedVersion 不匹配即放弃，版本较大者胜）；
     * false 走旧的双 set 路径（回滚开关）。summaryType 供收口摘要标记 final（M4-4）。</p>
     */
    private boolean saveSummaryChecked(String sessionId, int expectedVersion, String text,
                                        Long lastMessageId, int newVersion, String summaryType) {
        if (!props.isCasEnabled()) {
            saveSummary(sessionId, text, lastMessageId, newVersion);
            return true;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("lastMessageId", lastMessageId);
        meta.put("version", newVersion);
        if (summaryType != null) {
            meta.put("summaryType", summaryType);
        }
        long ttlSeconds = TimeUnit.DAYS.toSeconds(props.getSummaryTtlDays());
        Long r = redisTemplate.execute(SAVE_SUMMARY_CAS,
                List.of(summaryKey(sessionId), summaryMetaKey(sessionId)),
                String.valueOf(expectedVersion), text, JsonUtils.toJson(meta), String.valueOf(ttlSeconds));
        if (r == null || r != 1L) {
            log.warn("[SessionMemory] 摘要版本冲突放弃写入: sessionId={}, expectedVersion={}",
                    sessionId, expectedVersion);
            return false;
        }
        return true;
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
        String text = getSummaryInfo(sessionId).text();
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
