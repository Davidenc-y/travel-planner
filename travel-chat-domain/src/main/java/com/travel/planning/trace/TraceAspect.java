package com.travel.planning.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import com.travel.planning.memory.shortterm.SessionMemoryPort;

import java.util.UUID;

/**
 * Agent 追溯入口切面（F89）：AOP 覆盖 ChatService/ItineraryService 用户面入口。
 *
 * <p>只负责 begin/end 生命周期与基础字段（type/status/duration/endpoint）；
 * user/session/path/tokens 由服务层与 Agent 层填充（TraceContext）。</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class TraceAspect {

    private final AgentTraceCollector collector;
    private final TraceProperties properties;
    /** M3-9：请求内消息快照生命周期（与 TraceContext 同生命周期，finally 必清理） */
    private final SessionMemoryPort sessionMemoryPort;

    /** M3-1：模型名从配置读取（travel.ai.models.main），不再硬编码 */
    @Value("${travel.ai.models.main:qwen3.7-max}")
    private String modelName;

    // M7-8：ItineraryService 位于 travel-planning 模块，travel-chat-domain 不依赖它，
    // 切点字符串不能引用该符号（IDEA 静态分析报 Cannot resolve symbol；Maven 编译不校验
    // 字符串）。用同包通配 *Service.generate(..) 等价匹配——当前该包仅有
    // ItineraryService.generate，未来新增 generate 需确认是否应纳入追溯。
    @Around("execution(* com.travel.planning.service.ChatService.sendMessage(..))"
            + " || execution(* com.travel.planning.service.ChatService.runStream(..))"
            + " || execution(* com.travel.planning.service.*Service.generate(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        sessionMemoryPort.beginRequest();
        TraceContext.Holder holder = null;
        try {
            if (!properties.isEnabled()) {
                return pjp.proceed();
            }
            String method = pjp.getSignature().getName();
            boolean isGenerate = "generate".equals(method);
            String type = isGenerate ? "itinerary" : "chat";
            // M7-8：SSE/WebFlux 路径（runStream）单独标记流式端点；JSON 路径保持原样
            String endpoint = isGenerate
                    ? "POST /api/v1/itineraries/generate"
                    : "runStream".equals(method)
                            ? "POST /api/v1/chat/sessions/{id}/messages/stream"
                            : "POST /api/v1/chat/sessions/{id}/messages";
            String requestId = UUID.randomUUID().toString();
            holder = TraceContext.begin(requestId);
            holder.trace.setTraceType(type);
            holder.trace.setEndpoint(endpoint);
            holder.trace.setModelName(modelName);
            holder.path.add(type);
            Object result = pjp.proceed();
            holder.trace.setOutputLength(outputLengthOf(result));
            // M8-2：检索链路降级（如预检索失败返回 "[]"）时，成功响应标注 DEGRADED，
            // 让“知识库不可用但回答正常”的事件可观测（FAILED 会误伤用户面成功率）
            if (holder.degradedReason != null) {
                collector.end(holder, "DEGRADED", "DEGRADED:" + holder.degradedReason);
            } else {
                collector.end(holder, "SUCCESS", null);
            }
            return result;
        } catch (Throwable e) {
            // M7-8：轮次中断（TurnInterruptedException）不记 FAILED trace——
            // 与“中断不落库”语义一致；成功/其他异常照常记录
            if (!(e instanceof com.travel.planning.service.TurnInterruptedException)) {
                collector.end(holder, statusOf(e), e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage().substring(0,
                        Math.min(500, e.getMessage().length())));
            }
            throw e;
        } finally {
            sessionMemoryPort.endRequest();
            TraceContext.clear();
        }
    }

    /**
     * M4-7（前置修复 5）：异常链含 TimeoutException 时记 TIMEOUT（该状态枚举
     * 自 F89 定义以来无写入点，失败统计看板分母不准）。
     */
    public static String statusOf(Throwable e) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 16) {
            if (cur instanceof java.util.concurrent.TimeoutException) {
                return "TIMEOUT";
            }
            cur = cur.getCause();
            depth++;
        }
        return "FAILED";
    }

    private static int outputLengthOf(Object result) {
        if (result == null) {
            return 0;
        }
        String s = result.toString();
        return Math.min(s.length(), 100000);
    }
}
