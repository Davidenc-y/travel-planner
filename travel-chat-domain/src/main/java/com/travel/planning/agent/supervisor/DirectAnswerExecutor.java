package com.travel.planning.agent.supervisor;

import com.travel.core.guard.CircuitBreaker;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.trace.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M6-58/T9 Step3：F85 入口直答 / 回顾管线执行器（从 TravelSupervisorAgent 迁出）。
 *
 * <p>持有 chatModel/promptTemplates/circuitBreakerRegistry，实现四个入口方法
 * （阻塞/真 token 流 × 直答/回顾）及私有 LLM 调用收敛（M3-7 System+User 双消息、
 * 可选熔断；流式订阅前取许可的 M6-3 语义）。行为与迁移前逐字节等价。</p>
 *
 * <p>注意：不注入 TokenUsageInterceptor——直答/回顾路径按 F89 设计直接用
 * {@link TraceContext} 累计（applyDirectTokens），拦截器仅服务于 Supervisor
 * 整图路径（M6-55 T9 Step3 决策：避免注入死依赖）。</p>
 */
@Slf4j
public final class DirectAnswerExecutor {

    private final ChatModel chatModel;
    private final PromptTemplates promptTemplates;
    private final CircuitBreaker.Registry circuitBreakerRegistry;

    DirectAnswerExecutor(ChatModel chatModel,
                         PromptTemplates promptTemplates,
                         CircuitBreaker.Registry circuitBreakerRegistry) {
        this.chatModel = chatModel;
        this.promptTemplates = promptTemplates;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * F85：PROFILE/CHAT/FUNCTIONAL 意图的入口直答（不触发 supervisor），
     * 使用覆盖优先级 system 指令（会话最新确认/feedback > constraint > 画像）。
     */
    public TravelSupervisorAgent.PlanningResult answerDirect(String userInput, Long userId) {
        if (TraceContext.active()) {
            TraceContext.current().addPath("direct");
        }
        String system = promptTemplates.directAnswerSystem();
        ChatResponse direct = callDirect(system, userInput, true);
        String text = direct.getResult() != null && direct.getResult().getOutput() != null
                ? direct.getResult().getOutput().getText() : "";
        long tokens = direct.getMetadata() != null && direct.getMetadata().getUsage() != null
                ? direct.getMetadata().getUsage().getTotalTokens() : 0;
        log.info("[DirectAnswer] 入口直答: inputLength={}, answerLength={}, tokens={}",
                userInput == null ? 0 : userInput.length(), text.length(), tokens);
        applyDirectTokens(direct);
        return new TravelSupervisorAgent.PlanningResult(
                text.isBlank() ? "抱歉，暂时无法回答，请稍后重试。" : text.trim(), tokens);
    }

    /**
     * M6：PROFILE/CHAT/FUNCTIONAL 意图的入口直答——真 token 流。
     *
     * <p>与 {@link #answerDirect} 同 prompt/同 system 指令，但通过
     * {@code chatModel.stream} 逐增量回调 {@code tokenSink}；token 用量取流末
     * 累计 Usage（F27 口径）。空结果兜底文案同样回调，保证前端最终可见完整回答。</p>
     */
    public TravelSupervisorAgent.PlanningResult answerDirectStream(String userInput, Long userId,
                                                                   Consumer<String> tokenSink,
                                                                   TurnCancellation cancellation) {
        if (TraceContext.active()) {
            TraceContext.current().addPath("direct");
        }
        String system = promptTemplates.directAnswerSystem();
        return streamToResult(system, userInput, true, tokenSink, "入口直答",
                "抱歉，暂时无法回答，请稍后重试。", cancellation);
    }

    /**
     * F85：RECALL 意图的轻量回顾管线——itinerary_day 切片确定性骨架 + LLM 润色
     * （零编造、低 token）；无切片时确定性返回"未找到"，不调 LLM。
     */
    public TravelSupervisorAgent.PlanningResult answerRecall(
            String userInput, List<Map<String, Object>> sessionHits) {
        if (TraceContext.active()) {
            TraceContext.current().addPath("recall");
        }
        String skeleton = buildRecallSkeleton(sessionHits);
        String question = extractCurrentQuestion(userInput);
        if (skeleton.isBlank()) {
            String fallback = "未找到该行程记录，请确认您是否在本会话中生成过行程。";
            log.info("[Recall] 无行程切片，直接返回: {}", fallback);
            return new TravelSupervisorAgent.PlanningResult(fallback, 0);
        }
        String system = promptTemplates.recallSystem();
        ChatResponse direct = callDirect(system, skeleton + "\n\n用户问题：" + question, true);
        String text = direct.getResult() != null && direct.getResult().getOutput() != null
                ? direct.getResult().getOutput().getText() : "";
        long tokens = direct.getMetadata() != null && direct.getMetadata().getUsage() != null
                ? direct.getMetadata().getUsage().getTotalTokens() : 0;
        log.info("[Recall] 回顾管线完成: skeletonLen={}, answerLen={}, tokens={}",
                skeleton.length(), text.length(), tokens);
        applyDirectTokens(direct);
        return new TravelSupervisorAgent.PlanningResult(text.isBlank() ? skeleton : text.trim(), tokens);
    }

    /**
     * M6：RECALL 意图的轻量回顾管线——真 token 流。
     *
     * <p>无行程切片时确定性返回（同步回调一次完整文本）；有切片时走
     * {@code chatModel.stream} 逐增量回调。与 {@link #answerRecall} 语义一致。</p>
     */
    public TravelSupervisorAgent.PlanningResult answerRecallStream(
            String userInput, List<Map<String, Object>> sessionHits,
            Consumer<String> tokenSink, TurnCancellation cancellation) {
        if (TraceContext.active()) {
            TraceContext.current().addPath("recall");
        }
        String skeleton = buildRecallSkeleton(sessionHits);
        String question = extractCurrentQuestion(userInput);
        if (skeleton.isBlank()) {
            String fallback = "未找到该行程记录，请确认您是否在本会话中生成过行程。";
            Consumer<String> sink = tokenSink == null ? t -> { } : tokenSink;
            sink.accept(fallback);
            log.info("[Recall] 无行程切片，直接返回: {}", fallback);
            return new TravelSupervisorAgent.PlanningResult(fallback, 0);
        }
        String system = promptTemplates.recallSystem();
        return streamToResult(system, skeleton + "\n\n用户问题：" + question, true,
                tokenSink, "回顾管线", skeleton, cancellation);
    }

    /** F89：直答/回顾路径的 token 写入追溯上下文 */
    /** M3-7：直答调用收敛（System+User 消息构造 + 可选熔断） */
    ChatResponse callDirect(String system, String userText, boolean withBreaker) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(userText)));
        if (withBreaker) {
            return circuitBreakerRegistry.of("chat").call("chat", () -> chatModel.call(prompt));
        }
        return chatModel.call(prompt);
    }

    /**
     * M6：直答/回顾路径的真 token 流（响应式）。
     *
     * <p>熔断语义：订阅前经 {@link CircuitBreaker#call} 获取许可（OPEN 直接拒绝、
     * HALF_OPEN 单探测），与阻塞式 {@code call} 的三态语义保持一致；流建立即视为
     * 调用成功，流中途异常由调用方降级兜底（不重复计数，详见 M6-3 记录）。</p>
     */
    private Flux<ChatResponse> callDirectStream(String system, String userText, boolean withBreaker) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(userText)));
        if (!withBreaker) {
            return chatModel.stream(prompt);
        }
        CircuitBreaker breaker = circuitBreakerRegistry.of("chat");
        return Flux.defer(() -> {
            try {
                return breaker.call("chat", () -> chatModel.stream(prompt));
            } catch (CircuitBreaker.CircuitOpenException e) {
                return Flux.error(e);
            }
        });
    }

    /**
     * M6：消费 {@code chatModel.stream} 增量并聚合最终回答。
     *
     * <p>DashScope 流式每个 ChatResponse 的 text 为增量片段；Usage 为累计值，
     * 取最后一段非空 Usage 的 totalTokens（与 F27 口径一致）。</p>
     */
    private TravelSupervisorAgent.PlanningResult streamToResult(
            String system, String userText, boolean withBreaker,
            Consumer<String> tokenSink, String label, String blankFallback,
            TurnCancellation cancellation) {
        TurnCancellation cancel = cancellation == null ? TurnCancellation.NOOP : cancellation;
        cancel.throwIfCancelled();
        Consumer<String> sink = tokenSink == null ? t -> { } : tokenSink;
        StringBuilder sb = new StringBuilder();
        long[] tokens = {0};
        ChatResponse[] lastResponse = new ChatResponse[1];
        ReactiveBlockSupport.blockUntilDone(callDirectStream(system, userText, withBreaker), resp -> {
            lastResponse[0] = resp;
            String text = resp.getResult() != null && resp.getResult().getOutput() != null
                    ? resp.getResult().getOutput().getText() : null;
            if (text != null && !text.isBlank()) {
                sb.append(text);
                sink.accept(text);
            }
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null
                    && resp.getMetadata().getUsage().getTotalTokens() != null) {
                tokens[0] = resp.getMetadata().getUsage().getTotalTokens();
            }
        }, cancel, TravelSupervisorAgent.MAX_EXECUTION_SECONDS);
        String text = sb.toString().trim();
        if (lastResponse[0] != null) {
            applyDirectTokens(lastResponse[0]);
        }
        if (text.isBlank()) {
            sink.accept(blankFallback);
            log.info("[{}] 流式输出为空，返回兜底: len={}, tokens={}",
                    label, blankFallback.length(), tokens[0]);
            return new TravelSupervisorAgent.PlanningResult(blankFallback, tokens[0]);
        }
        log.info("[{}] 流式输出完成: answerLen={}, tokens={}",
                label, text.length(), tokens[0]);
        return new TravelSupervisorAgent.PlanningResult(text, tokens[0]);
    }

    /** F89：直答/回顾路径的 token 写入追溯上下文（阻塞/图流执行器共用） */
    static void applyDirectTokens(ChatResponse direct) {
        if (!TraceContext.active() || direct.getMetadata() == null
                || direct.getMetadata().getUsage() == null) {
            return;
        }
        Usage u = direct.getMetadata().getUsage();
        TraceContext.Holder h = TraceContext.current();
        h.promptTokens += u.getPromptTokens() != null ? u.getPromptTokens() : 0;
        h.completionTokens += u.getCompletionTokens() != null ? u.getCompletionTokens() : 0;
        h.totalTokens += u.getTotalTokens() != null ? u.getTotalTokens() : 0;
    }

    /** F85：从结构化切片提取 itinerary_day 骨架（已是最近一次行程过滤后的命中） */
    private static String buildRecallSkeleton(List<Map<String, Object>> sessionHits) {
        if (sessionHits == null || sessionHits.isEmpty()) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> hit : sessionHits) {
            if (!"itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")))) {
                continue;
            }
            String content = String.valueOf(hit.getOrDefault("content", "")).trim();
            if (!content.isBlank()) {
                lines.add(content);
            }
        }
        return String.join("\n", lines);
    }

    private static String extractCurrentQuestion(String userInput) {
        if (userInput == null) {
            return "";
        }
        int idx = userInput.lastIndexOf("【当前问题】");
        if (idx >= 0) {
            return userInput.substring(idx + "【当前问题】".length()).trim();
        }
        return userInput.trim();
    }
}
