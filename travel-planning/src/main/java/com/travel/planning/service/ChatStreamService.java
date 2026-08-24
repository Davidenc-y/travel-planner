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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * M6：聊天域流式 Pipeline。
 *
 * <p>复用 {@link ChatService#prepareStream}/{@link ChatService#runStream}，
 * 保证与 JSON 路径共用九步流水线与幂等语义；思考阶段经 listener 输出，
 * 最终回答经 {@link StreamingChunker} 分块输出。</p>
 */
@Slf4j
@Component
public class ChatStreamService extends AbstractStreamingPipeline {

    private final ChatService chatService;
    private final ChatStreamProperties props;

    public ChatStreamService(ChatService chatService, ChatStreamProperties props,
                             StreamMetrics metrics) {
        super(metrics);
        this.chatService = chatService;
        this.props = props;
    }

    @Override
    public StreamPreflight preflight(StreamRequest request) {
        try {
            ChatService.ChatStreamPrepared prepared = chatService.prepareStream(
                    request.userId(), request.sessionId(), request.input(),
                    request.clientMessageId());
            return StreamPreflight.ok(prepared);
        } catch (BusinessException e) {
            return StreamPreflight.fail(e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("[ChatStream] preflight 意外异常: sessionId={}", request.sessionId(), e);
            return StreamPreflight.fail(50000, "流式处理失败，请稍后重试");
        }
    }

    @Override
    protected Flux<StreamEvent> doStream(StreamRequest request, StreamPreflight preflight) {
        ChatService.ChatStreamPrepared prepared =
                (ChatService.ChatStreamPrepared) preflight.context();
        StreamMeta meta = new StreamMeta(newRequestId(), request.sessionId(),
                request.clientMessageId(), request.domain(), null, prepared.replay());
        if (prepared.replay()) {
            String replay = prepared.gate().replayResponse();
            int tokens = prepared.gate().replayTokens() == null
                    ? 0 : prepared.gate().replayTokens();
            return Flux.fromIterable(StreamingChunker.chunk(replay, props.getChunkMaxChars()))
                    .map(chunk -> StreamEvent.token(meta, chunk))
                    .concatWith(Flux.just(StreamEvent.done(meta, Map.of(
                            "sessionId", request.sessionId(),
                            "tokens", tokens,
                            "replayed", true))));
        }
        return Flux.<StreamEvent>create(sink -> {
            try {
                ChatService.ChatStreamResult result = chatService.runStream(prepared,
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
                                    sink.next(StreamEvent.token(meta, chunk));
                                }
                            }

                            @Override
                            public void onResponse(String response) {
                                for (String chunk : StreamingChunker.chunk(
                                        response, props.getChunkMaxChars())) {
                                    sink.next(StreamEvent.token(meta, chunk));
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
                sink.next(StreamEvent.done(meta, done));
                sink.complete();
            } catch (Exception e) {
                sink.next(StreamEvent.error(meta, 50000,
                        e.getMessage() == null ? "流式处理失败" : e.getMessage()));
                sink.complete();
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
