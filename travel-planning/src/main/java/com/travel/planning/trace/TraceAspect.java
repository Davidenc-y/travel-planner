package com.travel.planning.trace;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

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

    /** M3-1：模型名从配置读取（travel.ai.models.main），不再硬编码 */
    @Value("${travel.ai.models.main:qwen3.7-max}")
    private String modelName;

    @Around("execution(* com.travel.planning.service.ChatService.sendMessage(..))"
            + " || execution(* com.travel.planning.service.ItineraryService.generate(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
        if (!properties.isEnabled()) {
            return pjp.proceed();
        }
        String method = pjp.getSignature().getName();
        boolean isGenerate = "generate".equals(method);
        String type = isGenerate ? "itinerary" : "chat";
        String requestId = UUID.randomUUID().toString();
        TraceContext.Holder holder = TraceContext.begin(requestId);
        holder.trace.setTraceType(type);
        holder.trace.setEndpoint(isGenerate
                ? "POST /api/v1/itineraries/generate"
                : "POST /api/v1/chat/sessions/{id}/messages");
        holder.trace.setModelName(modelName);
        holder.path.add(type);
        try {
            Object result = pjp.proceed();
            holder.trace.setOutputLength(outputLengthOf(result));
            collector.end(holder, "SUCCESS", null);
            return result;
        } catch (Throwable e) {
            collector.end(holder, "FAILED", e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage().substring(0,
                    Math.min(500, e.getMessage().length())));
            throw e;
        } finally {
            TraceContext.clear();
        }
    }

    private static int outputLengthOf(Object result) {
        if (result == null) {
            return 0;
        }
        String s = result.toString();
        return Math.min(s.length(), 100000);
    }
}
