package com.travel.planning.stream;

import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
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

    private final StreamPayloadMapper payloadMapper;

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
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(event.type().name().toLowerCase())
                    .reconnectTime(3000L)
                    .data(payloadMapper.toJson(event));
            // A-P1/A-P2：仅 token/done 携带确定性 id（分块序号）；thinking/ping 不带 id，
            // 保证普通流与重放流 id 语义一致
            if (event.eventId() != null) {
                builder.id(event.eventId());
            }
            emitter.send(builder);
        } catch (Exception e) {
            // 客户端断开/序列化失败：上抛由订阅 onError 收口
            throw new IllegalStateException("SSE 发送失败", e);
        }
    }

}
