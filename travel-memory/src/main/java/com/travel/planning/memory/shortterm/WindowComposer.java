package com.travel.planning.memory.shortterm;

import com.travel.common.entity.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/**
 * 窗口/token 域协作件（MM-3.2，自 SessionMemoryServiceImpl 原样迁出）。
 *
 * <p>承载历史组装（composeHistoryContext/composeRecentWindow 的 buildLines）、
 * 轮数与全量 token 统计、统一 token 估算（M3-5）与按预算截断。方法体与迁出前
 * 逐字一致；会话消息经 {@link Supplier} 惰性取数——由调用方
 * （SessionMemoryServiceImpl）提供其请求内消息快照入口，行为与迁出前一致。</p>
 *
 * <p>本类同时是 token 工具的单一同源点（SummaryAssembler 的输出硬约束经本类
 * 调用，替代 MM-3.1 的临时同源副本）。</p>
 */
@Component
public class WindowComposer {

    private final ShortTermMemoryProperties props;

    public WindowComposer(ShortTermMemoryProperties props) {
        this.props = props;
    }

    public String composeHistoryContext(String sessionId, Supplier<List<ChatMessage>> messagesSupplier,
                                        int maxTurns) {
        return buildLines(sessionId, messagesSupplier, maxTurns * 2, true, props.getHistoryMaxTokens());
    }

    public String composeRecentWindow(String sessionId, Supplier<List<ChatMessage>> messagesSupplier,
                                      int turns) {
        return buildLines(sessionId, messagesSupplier, Math.max(1, turns) * 2, false, Integer.MAX_VALUE);
    }

    public int countUserTurns(String sessionId, Supplier<List<ChatMessage>> messagesSupplier) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        List<ChatMessage> messages = messagesSupplier.get();
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

    public int totalHistoryTokens(String sessionId, Supplier<List<ChatMessage>> messagesSupplier) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        List<ChatMessage> messages = messagesSupplier.get();
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimateTokens(m.getContent()) + 4;
        }
        return total;
    }

    /** M3-5：统一 token 估算（TextTokens 与本地口径一致）。 */
    public int estimateTokens(String text) {
        return com.travel.common.util.TextTokens.estimate(text);
    }

    /** 按 token 预算截断文本（语义句/字符级，追加"…（已截断）"标记；F58/B1.2）。 */
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

    private String buildLines(String sessionId, Supplier<List<ChatMessage>> messagesSupplier,
                              int maxLines, boolean withHeader, int maxTokens) {
        if (sessionId == null || sessionId.isBlank() || maxLines <= 0) {
            return "";
        }
        List<ChatMessage> messages = messagesSupplier.get();
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
}
