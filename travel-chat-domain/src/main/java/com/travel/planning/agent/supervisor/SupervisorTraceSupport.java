package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.travel.planning.trace.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M6-58/T9 Step4：Supervisor 追溯上下文写入工具（从 TravelSupervisorAgent 迁出）。
 *
 * <p>纯静态、零状态；F89 调用路径与 token 累计语义逐字节等价，被阻塞/图流执行器共用。</p>
 */
final class SupervisorTraceSupport {

    private static final Logger log = LoggerFactory.getLogger(SupervisorTraceSupport.class);

    private SupervisorTraceSupport() {
    }

    /** F89：token 累计写入追溯上下文 */
    static void applyTraceTokens(long[] usage) {
        if (!TraceContext.active()) {
            return;
        }
        TraceContext.Holder h = TraceContext.current();
        h.promptTokens += usage[0];
        h.completionTokens += usage[1];
        h.totalTokens += usage[2];
    }

    /** S-B7：首 token 耗时写入追溯上下文（null 安全村；非流式不写）。
     *  T-3a：跳过路径 DEBUG 诊断（thread/active/ttft——执行线程与 begin 线程不一致时
     *  active=false 此前静默跳过无日志可证；DEBUG 保留，供审计相起服相定位） */
    static void applyTraceTtft(Long ttftMs) {
        if (ttftMs == null) {
            return;
        }
        if (!TraceContext.active()) {
            log.debug("[AgentTrace][ttft-diag] holder 不可用跳过（线程不一致预期）: thread={}, active=false, ttftMs={}",
                    Thread.currentThread().getName(), ttftMs);
            return;
        }
        TraceContext.current().ttftMs = ttftMs;
    }

    /** F89：调用路径 [supervisor, preference_analysis, ...] 写入追溯上下文 */
    static void applyTracePath(OverAllState state) {
        if (!TraceContext.active()) {
            return;
        }
        TraceContext.Holder h = TraceContext.current();
        h.addPath("supervisor");
        String[][] sections = {
                {"preference", "preference_analysis"},
                {"attractions", "attraction_filter"},
                {"routePlan", "route_arrangement"},
                {"budgetEstimate", "budget_estimation"},
        };
        for (String[] s : sections) {
            if (!SupervisorResponseSupport.toText(state.value(s[0])).isBlank()) {
                h.addPath(s[1]);
            }
        }
    }
}
