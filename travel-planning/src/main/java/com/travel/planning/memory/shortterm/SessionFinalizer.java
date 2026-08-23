package com.travel.planning.memory.shortterm;

import com.travel.common.entity.ChatSession;
import com.travel.planning.config.LlmGovernor;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 会话收口器（M4-4/P1-1，R2 方案 1.1"零队列版"）。
 *
 * <p>设计要点（替代 M4-0-Final 的"独立有界队列"——无持久化载体且组件过重）：</p>
 * <ul>
 *   <li><b>同步尽力收口</b>：close 请求内直接执行（{@link LlmGovernor#callWithPermit}，
 *       拿不到许可抛异常由本类转 false），同步等待上限
 *       {@code finalize-sync-wait-seconds}（默认 15s）；超时后后台任务继续完成，
 *       最终经 CAS 与 summary_final 幂等写落盘，不丢结果；</li>
 *   <li><b>隐式待办</b>：{@code status=ARCHIVED AND summary_final IS NULL} 即收口未完成，
 *       无需待办表/队列；</li>
 *   <li><b>启动补偿</b>：{@code ApplicationReadyEvent} 扫描待办（排除 10 分钟内归档的，
 *       避开刚 close 在途的）逐个补跑——进程重启待办不丢。</li>
 * </ul>
 *
 * <p>P2 空闲扫描器（finalize-idle-minutes）将复用本类的补偿入口，并顺带清理
 * 幂等记录 TTL（M4-3 遗留项）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionFinalizer {

    private static final ExecutorService FINALIZE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    /** 启动补偿：排除 N 分钟内归档的会话（避开 close 同步收口在途窗口） */
    private static final int COMPENSATE_SKIP_MINUTES = 10;
    /** 启动补偿：单轮最多补跑数（防大量积压拖慢启动后的治理额度） */
    private static final int COMPENSATE_BATCH_LIMIT = 20;

    private final SessionMemoryPort sessionMemoryPort;
    private final SessionStorePort sessionStorePort;
    private final LlmGovernor llmGovernor;
    private final ShortTermMemoryProperties props;

    /**
     * 归档后的收口入口（close 调用）：同步等待上限内尽力完成，超时/失败返回 false
     * （后台任务可能仍在执行并最终落盘；未落盘则由启动补偿/空闲扫描兜底）。
     */
    public boolean finalizeSession(String sessionId) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(
                () -> doFinalize(sessionId), FINALIZE_EXECUTOR);
        try {
            return future.get(props.getFinalizeSyncWaitSeconds() + 1, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("[SessionFinalize] 收口超时转后台继续: sessionId={}", sessionId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[SessionFinalize] 收口等待被中断: sessionId={}", sessionId);
            return false;
        } catch (Exception e) {
            log.warn("[SessionFinalize] 收口失败: sessionId={}, error={}", sessionId, e.getMessage());
            return false;
        }
    }

    /** 实际收口：许可治理（callWithPermit，拿不到许可不丢弃语义→转 false 留待办）+ 持久化 */
    private boolean doFinalize(String sessionId) {
        try {
            return llmGovernor.callWithPermit("session-finalize", () -> {
                boolean ok = sessionMemoryPort.finalizeSummary(sessionId);
                if (ok) {
                    // 持久层补偿：final 摘要落 MySQL（幂等首写）；空会话落空串标记防重复扫描
                    String summary = sessionMemoryPort.getSummaryOrEmpty(sessionId);
                    sessionStorePort.updateSummaryFinal(sessionId,
                            summary == null ? "" : summary);
                }
                return ok;
            });
        } catch (IllegalStateException e) {
            // 并发许可已满：不丢弃语义——保留隐式待办，由补偿/扫描兜底
            log.warn("[SessionFinalize] 许可不足转待办: sessionId={}, {}", sessionId, e.getMessage());
            return false;
        }
    }

    /**
     * 启动补偿：补跑"已归档但收口未完成"的会话（进程重启待办不丢的保障）。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void compensateOnStartup() {
        try {
            LocalDateTime updatedBefore = LocalDateTime.now().minusMinutes(COMPENSATE_SKIP_MINUTES);
            List<ChatSession> pending = sessionStorePort.findArchivedWithoutFinal(
                    updatedBefore, COMPENSATE_BATCH_LIMIT);
            if (pending.isEmpty()) {
                return;
            }
            log.info("[SessionFinalize] 启动补偿: 待收口会话 {} 个", pending.size());
            for (ChatSession s : pending) {
                // 直接复用 doFinalize（已在虚拟线程上，逐个受 Governor 限流）
                boolean ok = doFinalize(s.getSessionId());
                log.info("[SessionFinalize] 启动补偿收口: sessionId={}, ok={}", s.getSessionId(), ok);
            }
        } catch (Exception e) {
            log.warn("[SessionFinalize] 启动补偿异常（不影响启动）: {}", e.getMessage());
        }
    }
}
