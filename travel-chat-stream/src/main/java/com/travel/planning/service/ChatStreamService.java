package com.travel.planning.service;

import com.travel.common.exception.BusinessException;
import com.travel.core.stream.AbstractStreamingPipeline;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import com.travel.core.stream.StreamMetrics;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.core.stream.StreamingChunker;
import com.travel.planning.stream.ChatStreamProperties;
import com.travel.planning.stream.StreamErrorMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M6：聊天域流式 Pipeline（中立模块，不依赖 travel-planning）。
 *
 * <p>通过 {@link ChatStreamExecutor} 端口复用九步流水线与幂等语义；
 * 思考阶段经 listener 输出，最终回答经 {@link StreamingChunker} 分块输出；
 * A-P2 支持 COMPLETED 重放的 Last-Event-ID 确定性续传。</p>
 */
@Slf4j
@Component
public class ChatStreamService extends AbstractStreamingPipeline {

    private final ChatStreamExecutor executor;
    private final ChatStreamProperties props;
    // M6-40：SSE/WebFlux 断开即取消在途轮次（响应式 dispose + 标记）
    private final TurnCancellationRegistry cancellationRegistry;

    public ChatStreamService(ChatStreamExecutor executor, ChatStreamProperties props,
                             StreamMetrics metrics,
                             TurnCancellationRegistry cancellationRegistry) {
        super(metrics);
        this.executor = executor;
        this.props = props;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Override
    public StreamPreflight preflight(StreamRequest request) {
        try {
            ChatStreamExecutor.ChatStreamPrepared prepared = executor.prepareStream(
                    request.userId(), request.sessionId(), request.input(),
                    request.clientMessageId(), modelOf(request));
            return StreamPreflight.ok(prepared);
        } catch (BusinessException e) {
            return StreamPreflight.fail(e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("[ChatStream] preflight 意外异常: sessionId={}", request.sessionId(), e);
            return StreamPreflight.fail(50000, "流式处理失败，请稍后重试");
        }
    }

    /** M7 Batch 2：从 StreamRequest.attributes 读取请求级 model（无则 null）。 */
    private static String modelOf(StreamRequest request) {
        Object model = request.attributes().get("model");
        return model instanceof String s && !s.isBlank() ? s : null;
    }

    @Override
    protected Flux<StreamEvent> doStream(StreamRequest request, StreamPreflight preflight) {
        ChatStreamExecutor.ChatStreamPrepared prepared =
                (ChatStreamExecutor.ChatStreamPrepared) preflight.context();
        StreamMeta meta = new StreamMeta(newRequestId(), request.sessionId(),
                request.clientMessageId(), request.domain(), null, prepared.replay());
        if (prepared.replay()) {
            String replay = prepared.gate().replayResponse();
            int tokens = prepared.gate().replayTokens() == null
                    ? 0 : prepared.gate().replayTokens();
            // A-P2：确定性分块 + Last-Event-ID 续传（分块下标即事件 id，无需落库）
            List<String> chunks = StreamingChunker.chunk(replay, props.getChunkMaxChars());
            int doneId = chunks.size() + 1;
            int from = replayFrom(request.lastEventId(), doneId);
            return Flux.range(from, chunks.size() - from)
                    .map(i -> StreamEvent.token(meta, chunks.get(i), String.valueOf(i + 1)))
                    .concatWith(Flux.just(StreamEvent.done(meta, Map.of(
                            "sessionId", request.sessionId(),
                            "tokens", tokens,
                            "replayed", true), String.valueOf(doneId))));
        }
        // M6-10 修正：token 事件 id = 分块序号（1-based），done id = 序号+1；
        // thinking/ping 不带 id，保证普通流与重放流 id 语义一致（断线续传可映射）
        AtomicInteger tokenSeq = new AtomicInteger(0);
        return Flux.<StreamEvent>create(sink -> {
            // M6-40：客户端断开（abort/切会话/关页）→ 取消在途轮次
            sink.onCancel(() -> cancellationRegistry.cancel(request.clientMessageId()));
            try {
                ChatStreamExecutor.ChatStreamResult result = executor.runStream(prepared,
                        new ChatProgressListener() {
                            @Override
                            public void onThinking(String stage, String message) {
                                sink.next(StreamEvent.thinking(meta, stage, message));
                            }

                            @Override
                            public void onToken(String text) {
                                if (text == null || text.isEmpty()) {
                                    return;
                                }
                                for (String chunk : StreamingChunker.chunk(
                                        text, props.getChunkMaxChars())) {
                                    sink.next(StreamEvent.token(meta, chunk,
                                            String.valueOf(tokenSeq.incrementAndGet())));
                                }
                            }

                            @Override
                            public void onResponse(String response) {
                                for (String chunk : StreamingChunker.chunk(
                                        response, props.getChunkMaxChars())) {
                                    sink.next(StreamEvent.token(meta, chunk,
                                            String.valueOf(tokenSeq.incrementAndGet())));
                                }
                            }
                        });
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("sessionId", request.sessionId());
                done.put("messageId", result.assistantMessageId());
                done.put("tokens", result.aiTokens());
                if (result.sessionTitle() != null) {
                    done.put("sessionTitle", result.sessionTitle());
                }
                done.put("replayed", false);
                sink.next(StreamEvent.done(meta, done,
                        String.valueOf(tokenSeq.get() + 1)));
                sink.complete();
            } catch (Exception e) {
                // M8-9h：业务异常（如模型额度不足 40303）透传业务码与友好文案，
                // 前端据此展示“模型额度不足”明确提示；其余异常仍按 50000 原始信息
                if (e instanceof BusinessException be) {
                    sink.next(StreamEvent.error(meta, be.getCode(), be.getMessage()));
                } else {
                    sink.next(StreamEvent.error(meta, 50000,
                            e.getMessage() == null ? "流式处理失败" : e.getMessage()));
                }
                sink.complete();
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * A-P2：把 Last-Event-ID（1-based 事件 id）映射为 0-based 起始下标。
     *
     * <p>标准 SSE 语义：Last-Event-ID=N 表示客户端已收到 id=N 的事件，
     * 服务端从 id=N+1 开始续传；即起始下标 = N（0-based），避免重复发送最后一块。</p>
     */
    private static int replayFrom(String lastEventId, int doneId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            int id = Integer.parseInt(lastEventId.trim());
            if (id <= 0) {
                return 0;
            }
            return Math.min(id, doneId - 1);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
