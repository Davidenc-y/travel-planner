package com.travel.planning.memory.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * T-5a：supervisor 子代理结果台账（中断重试细粒度化的 Redis 临时态，方案路线 B）。
 *
 * <p>图流执行中逐节点捕获已完成子代理输出（T-5b 接线），同键重试时种子注入
 * state map（DedupSubAgentHook 既有语义自动短路，零框架依赖）。结构：
 * Redis hash {@code chat:supledger:{sessionId}:{clientMessageId}}，
 * field=子代理 outputKey（{@link #NODE_TO_OUTPUT_KEY} 四映射，以
 * AbstractReactSubAgent 实际 outputKey 预检为准）、value=String 归一化文本。
 * TTL 30min 对齐断点（首写时 expire，非滑动）；生命周期与断点同生共死
 * （T-5e 两清理点对齐 M6-36）。异常 fail-open：台账失败不阻断主流程
 * （回退整图重跑=现状行为，台账只是优化不是正确性依赖）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SupervisorResultLedger {

    static final String LEDGER_PREFIX = "chat:supledger:";
    static final Duration LEDGER_TTL = Duration.ofMinutes(30);

    /** 节点名→子代理 outputKey（四子代理；T-5b 捕获与 T-5c 种子共用） */
    public static final Map<String, String> NODE_TO_OUTPUT_KEY = Map.of(
            "preference_analysis", "preference",
            "attraction_filter", "attractions",
            "route_arrangement", "routePlan",
            "budget_estimation", "budgetEstimate");

    private final StringRedisTemplate redisTemplate;

    static String ledgerKey(String sessionId, String clientMessageId) {
        return LEDGER_PREFIX + sessionId + ":" + clientMessageId;
    }

    /**
     * 记录单个已完成子代理输出。首写时设置 30min TTL（对齐断点，非滑动）；
     * 归一化后为空则跳过。异常 fail-open（WARN 不抛）。
     */
    public void record(String sessionId, String clientMessageId, String outputKey, String text) {
        if (sessionId == null || clientMessageId == null || outputKey == null) {
            return;
        }
        String normalized = normalize(text);
        if (normalized == null) {
            return;
        }
        try {
            String key = ledgerKey(sessionId, clientMessageId);
            boolean firstWrite = !Boolean.TRUE.equals(redisTemplate.hasKey(key));
            redisTemplate.opsForHash().put(key, outputKey, normalized);
            if (firstWrite) {
                redisTemplate.expire(key, LEDGER_TTL);
            }
        } catch (Exception e) {
            log.warn("[ResumeLedger] 台账记录失败（fail-open，回退整图重跑）: sessionId={}, outputKey={}, error={}",
                    sessionId, outputKey, e.getMessage());
        }
    }

    /** 加载同键重试可复用的全部子代理输出（无/失败=空 map，fail-open）。 */
    public Map<String, String> loadAll(String sessionId, String clientMessageId) {
        if (sessionId == null || clientMessageId == null) {
            return Collections.emptyMap();
        }
        try {
            Map<Object, Object> entries =
                    redisTemplate.opsForHash().entries(ledgerKey(sessionId, clientMessageId));
            if (entries == null || entries.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> result = new java.util.HashMap<>();
            entries.forEach((field, value) -> {
                if (field != null && value != null) {
                    result.put(String.valueOf(field), String.valueOf(value));
                }
            });
            return result;
        } catch (Exception e) {
            log.warn("[ResumeLedger] 台账加载失败（fail-open，回退整图重跑）: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** 单清（轮次正常完成时的 best-effort 清理，T-5b finally 调用）。 */
    public void clear(String sessionId, String clientMessageId) {
        if (sessionId == null || clientMessageId == null) {
            return;
        }
        try {
            redisTemplate.delete(ledgerKey(sessionId, clientMessageId));
        } catch (Exception e) {
            log.warn("[ResumeLedger] 台账单清失败（fail-open）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * session 级清（新轮失效对齐，T-5e 挂 ChatGateSupport M6-36 点）。
     * 实现镜像 {@code ChatBreakpointStore.clearSessionBreakpoints} 的 keys 扫描模式。
     */
    public void clearSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.keys(LEDGER_PREFIX + sessionId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[ResumeLedger] 台账 session 清理失败（fail-open）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /**
     * String 归一化——禁止 Optional[...] 字面量入库（行程路径快照归一化先例；
     * T-5b 侧已先经 SupervisorResponseSupport.toText，此处兜底防双层包装）。
     * 空串/Optional.empty/纯空白 → null（不入库）。
     */
    static String normalize(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.startsWith("Optional[") && t.endsWith("]")) {
            t = t.substring("Optional[".length(), t.length() - 1).trim();
            if (t.isEmpty() || "empty".equals(t)) {
                return null;
            }
        }
        return t.isEmpty() ? null : t;
    }
}
