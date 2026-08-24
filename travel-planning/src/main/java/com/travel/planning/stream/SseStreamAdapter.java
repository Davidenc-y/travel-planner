package com.travel.planning.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * M6：Flux&lt;StreamEvent&gt; → Spring MVC SseEmitter 传输适配器。
 *
 * <p>职责：事件序列化、keepalive、超时、断连清理；领域 Pipeline 不感知传输层。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseStreamAdapter {

    private final ObjectMapper objectMapper;

    public SseEmitter toEmitter(Flux<StreamEvent> events, long timeoutMs, long keepaliveMs) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        // M6：share() 保证保活流与业务流共享同一次上游订阅，避免流水线重复执行
        Flux<StreamEvent> shared = events.share();
        Flux<StreamEvent> heartbeats = Flux.interval(Duration.ofMillis(Math.max(1, keepaliveMs)))
                .map(i -> StreamEvent.ping(new StreamMeta("", "", "", "chat", null, false)))
                .takeUntilOther(shared.then().onErrorComplete());
        AtomicBoolean completed = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (completed.compareAndSet(false, true)) {
                emitter.complete();
            }
        };
        Disposable disposable = Flux.merge(shared, heartbeats)
                .subscribe(
                        ev -> send(emitter, ev),
                        err -> {
                            log.warn("[SseStream] 流式订阅异常: {}", err.getMessage());
                            if (completed.compareAndSet(false, true)) {
                                emitter.completeWithError(err);
                            }
                        },
                        finish
                );
        emitter.onCompletion(disposable::dispose);
        emitter.onTimeout(() -> {
            disposable.dispose();
            finish.run();
        });
        return emitter;
    }

    private void send(SseEmitter emitter, StreamEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type().name().toLowerCase())
                    .data(objectMapper.writeValueAsString(buildData(event))));
        } catch (Exception e) {
            // 客户端断开/序列化失败：上抛由订阅 onError 收口
            throw new IllegalStateException("SSE 发送失败", e);
        }
    }

    /**
     * M6：按事件类型构造 SSE data 载荷（与前端协议一致）。
     *
     * <p>thinking → {@code {stage,message}}；token/done/error → 事件 data 透传；
     * ping → 空对象。不把整个 StreamEvent 记录序列化到 data，避免前端解析错位。</p>
     */
    static Map<String, Object> buildData(StreamEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event.type()) {
            case THINKING -> {
                payload.put("stage", event.stage() == null ? "" : event.stage());
                payload.put("message", event.message() == null ? "" : event.message());
            }
            case TOKEN, DONE, ERROR -> {
                if (event.data() instanceof Map<?, ?> data) {
                    data.forEach((k, v) -> payload.put(String.valueOf(k), v));
                } else if (event.data() != null) {
                    payload.put("value", event.data());
                }
            }
            default -> {
                // PING：空对象
            }
        }
        return payload;
    }
}
