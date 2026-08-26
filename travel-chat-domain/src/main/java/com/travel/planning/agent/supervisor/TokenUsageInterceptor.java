package com.travel.planning.agent.supervisor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.travel.planning.service.TurnCancellation;
import com.travel.planning.service.TurnCancellationRegistry;
import com.travel.planning.service.TurnInterruptedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * F27：模型调用 token 用量采集拦截器。
 *
 * <p>注册在 SupervisorAgent 的 mainAgent 与 4 个子 Agent 上，位于
 * {@code AgentLlmNode} 调用链外层。每次 LLM 调用的真实用量只存在于
 * {@code ChatResponse.getMetadata().getUsage()}（DashScope 实证），拦截器在此累加
 * {@code totalTokens}。M6-22：同步响应走 {@code getChatResponse()} 分支；
 * 流式响应（supervisor.stream）的 {@code getMessage()} 为 {@code Flux<ChatResponse>}，
 * 对 Flux 做 side-effect 包装，在 {@code doOnComplete} 时按最后一个非空 Usage
 * （流式 chunk 为累计值）累加，语义与 F27 一致。</p>
 *
 * <p>按请求 ID 累加（{@link ConcurrentHashMap}）：请求 ID 经
 * {@code RunnableConfig} 的 metadata 传播（metadata 不可变且随子图配置复制，
 * graph-core 源码实证），不依赖线程局部性，支持并发请求隔离；未 begin() 的请求
 * （如行程工作流）自动跳过，互不干扰。</p>
 */
@Slf4j
@Component
public class TokenUsageInterceptor extends ModelInterceptor {

    /** RunnableConfig metadata 中传递请求 ID 的键。 */
    public static final String REQUEST_ID_KEY = "travel_request_id";

    /**
     * M6-42：RunnableConfig metadata 中传递轮次取消 key（clientMessageId）的键。
     * 由 TravelSupervisorAgent 构造 config 时写入；拦截器据此查取消登记表，
     * 命中已取消则在发起下一次 LLM 调用前短路。
     */
    public static final String TURN_CANCELLATION_KEY = "travel_turn_cancellation_key";

    private final ConcurrentMap<String, Long> totals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> promptTotals = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> completionTotals = new ConcurrentHashMap<>();
    private final TurnCancellationRegistry cancellationRegistry;

    public TokenUsageInterceptor(TurnCancellationRegistry cancellationRegistry) {
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public String getName() {
        return "tokenUsageInterceptor";
    }

    /** 开启一次请求的 token 累计。 */
    public void begin(String requestId) {
        totals.put(requestId, 0L);
        promptTotals.put(requestId, 0L);
        completionTotals.put(requestId, 0L);
    }

    /** 结束请求并返回累计的 totalTokens；重复调用返回 0。 */
    public long endAndGet(String requestId) {
        Long value = totals.remove(requestId);
        promptTotals.remove(requestId);
        completionTotals.remove(requestId);
        return value != null ? value : 0L;
    }

    /**
     * F89：取出但不清理（供追溯在服务结束时读取 prompt/completion/total）。
     *
     * @return long[]{prompt, completion, total}
     */
    public long[] peek(String requestId) {
        return new long[]{
                promptTotals.getOrDefault(requestId, 0L),
                completionTotals.getOrDefault(requestId, 0L),
                totals.getOrDefault(requestId, 0L),
        };
    }

    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        // M6-42：取消短路——图内下一次 LLM 调用前检查登记表；
        // 无 key / 未登记（行程工作流等其它使用者）跳过，行为不变。
        TurnCancellation cancellation = cancellationOf(request);
        if (cancellation != null && cancellation.isCancelled()) {
            throw new TurnInterruptedException("轮次已中断");
        }
        ModelResponse response = handler.call(request);
        if (response == null) {
            return response;
        }
        String requestId = request.getContext() == null
                ? null
                : (String) request.getContext().get(REQUEST_ID_KEY);
        if (requestId == null) {
            return response;
        }

        // 非流式：现有 F27 逻辑（getChatResponse() 携带完整 Usage）
        if (response.getChatResponse() != null) {
            accumulate(requestId, response.getChatResponse().getMetadata().getUsage());
            return response;
        }

        // M6-22 流式：getMessage() 为 Flux<ChatResponse>，取最后一个非空 Usage（累计值）
        Object message = response.getMessage();
        if (message instanceof Flux<?> flux) {
            AtomicReference<Usage> lastUsage = new AtomicReference<>();
            Flux<?> tapped = flux
                    .doOnNext(chunk -> captureLastUsage(chunk, lastUsage))
                    .doOnComplete(() -> accumulate(requestId, lastUsage.get()));
            return new ModelResponse(tapped);
        }
        return response;
    }

    /** 从 ModelRequest.context（= RunnableConfig metadata 拷贝）解析取消令牌。 */
    private TurnCancellation cancellationOf(ModelRequest request) {
        if (cancellationRegistry == null || request.getContext() == null) {
            return null;
        }
        Object key = request.getContext().get(TURN_CANCELLATION_KEY);
        if (!(key instanceof String s) || s.isBlank()) {
            return null;
        }
        return cancellationRegistry.get(s);
    }

    /** 流式 chunk 侧写：只保留最后一个非空 Usage（DashScope 流式 usage 为累计值）。 */
    private static void captureLastUsage(Object chunk, AtomicReference<Usage> lastUsage) {
        try {
            if (chunk instanceof ChatResponse chatResponse
                    && chatResponse.getMetadata() != null) {
                Usage usage = chatResponse.getMetadata().getUsage();
                if (usage != null && usage.getTotalTokens() != null) {
                    lastUsage.set(usage);
                }
            }
        } catch (Exception e) {
            // side-effect 不允许抛出，避免破坏图流
            log.warn("流式 token 采集忽略异常: {}", e.getMessage());
        }
    }

    /** 按请求 ID 累加一次 LLM 调用的用量（同步/流式共用）。 */
    private void accumulate(String requestId, Usage usage) {
        if (usage == null || usage.getTotalTokens() == null) {
            return;
        }
        totals.computeIfPresent(requestId, (k, current) -> current + usage.getTotalTokens());
        if (usage.getPromptTokens() != null) {
            promptTotals.computeIfPresent(requestId, (k, current) -> current + usage.getPromptTokens());
        }
        if (usage.getCompletionTokens() != null) {
            completionTotals.computeIfPresent(requestId,
                    (k, current) -> current + usage.getCompletionTokens());
        }
    }
}
