package com.travel.planning.memory.shortterm;

import com.travel.common.entity.ChatMessage;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短期会话记忆实现（F50/Phase A + F55/B1 + F57 全量汇总 + F58/B1.2 滚动/校验/硬约束）。
 *
 * <p>MM-3 起为分域薄壳（MM-3.2 完成收敛）：摘要域 → {@link SummaryAssembler}、
 * 窗口/token 域 → {@link WindowComposer}（均构造注入，方法体零变更迁移）；本类仅保留
 * {@link SessionMemoryPort} 委托与请求内消息快照（ThreadLocal，经
 * {@link java.util.function.Supplier} 提供给两协作件，"同一请求多次读取只查一次库、
 * 异步线程各自独立加载"语义不变）。Port 签名零变更（E-21 冻结）。</p>
 *
 * <p>读取 t_chat_message（只读不改原文）；摘要存 Redis：
 * {@code session:{id}:summary}（文本）+ {@code session:{id}:summary:meta}（游标/版本）。</p>
 */
@Slf4j
@Service
public class SessionMemoryServiceImpl implements SessionMemoryPort {

    // F67/B3-1：消息读取收口到 SessionStorePort（不直连 Mapper）
    private final SessionStorePort sessionStorePort;
    // MM-3：两域协作件（摘要/窗口）
    private final SummaryAssembler summaryAssembler;
    private final WindowComposer windowComposer;

    /** M3-9：请求内消息快照（同一请求多次读取只查一次库；异步线程各自独立加载） */
    private final ThreadLocal<Map<String, List<ChatMessage>>> requestMessages =
            ThreadLocal.withInitial(LinkedHashMap::new);

    public SessionMemoryServiceImpl(SessionStorePort sessionStorePort,
                                    SummaryAssembler summaryAssembler,
                                    WindowComposer windowComposer) {
        this.sessionStorePort = sessionStorePort;
        this.summaryAssembler = summaryAssembler;
        this.windowComposer = windowComposer;
    }

    @Override
    public void beginRequest() {
        requestMessages.get().clear();
    }

    @Override
    public void endRequest() {
        requestMessages.remove();
    }

    private List<ChatMessage> messagesOf(String sessionId) {
        return requestMessages.get().computeIfAbsent(sessionId, sessionStorePort::listMessages);
    }

    @Override
    public String composeHistoryContext(String sessionId, int maxTurns) {
        return windowComposer.composeHistoryContext(sessionId, () -> messagesOf(sessionId), maxTurns);
    }

    @Override
    public String getSummaryOrEmpty(String sessionId) {
        return summaryAssembler.getSummaryInfo(sessionId).text();
    }

    @Override
    public SessionMemoryPort.SummaryInfo getSummaryInfo(String sessionId) {
        return summaryAssembler.getSummaryInfo(sessionId);
    }

    @Override
    public void summarizeAsync(String sessionId) {
        // Supplier 惰性取数：后台线程上 ThreadLocal 缓存为空 → 各自独立加载（语义与拆分前一致）
        summaryAssembler.summarizeAsync(sessionId, () -> messagesOf(sessionId));
    }

    @Override
    public boolean finalizeSummary(String sessionId) {
        return summaryAssembler.finalizeSummary(sessionId, () -> messagesOf(sessionId));
    }

    @Override
    public String composeRecentWindow(String sessionId, int turns) {
        return windowComposer.composeRecentWindow(sessionId, () -> messagesOf(sessionId), turns);
    }

    @Override
    public int countUserTurns(String sessionId) {
        return windowComposer.countUserTurns(sessionId, () -> messagesOf(sessionId));
    }

    @Override
    public int totalHistoryTokens(String sessionId) {
        return windowComposer.totalHistoryTokens(sessionId, () -> messagesOf(sessionId));
    }

    @Override
    public int estimateTokens(String text) {
        return windowComposer.estimateTokens(text);
    }

    @Override
    public String truncateByTokens(String text, int maxTokens) {
        return windowComposer.truncateByTokens(text, maxTokens);
    }
}
