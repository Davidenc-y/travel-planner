package com.travel.webmvc.stream;

import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import com.travel.planning.stream.StreamPayloadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * M6-19：Flux&lt;StreamEvent&gt; → Spring MVC SseEmitter 传输适配器（共享模块）。
 *
 * <p>职责：事件序列化、keepalive、超时、断连清理；领域 Pipeline 不感知传输层。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseStreamAdapter {

    private final StreamPayloadMapper payloadMapper;

    public SseEmitter toEmitter(Flux<StreamEvent> events, long timeoutMs, long keepaliveMs) {
        return toEmitter(events, timeoutMs, keepaliveMs, null);
    }

    /**
     * M6-40：带断开回调的 SSE 适配（客户端断开/超时时取消在途轮次）。
     */
    public SseEmitter toEmitter(Flux<StreamEvent> events, long timeoutMs, long keepaliveMs,
                                Runnable onCancel) {
        SseEmitter emitter = new SseEmitter(timeoutMs);
        // M6：share() 保证保活流与业务流共享同一次上游订阅，避免流水线重复执行
        Flux<StreamEvent> shared = events.share();
        Flux<StreamEvent> heartbeats = Flux.interval(Duration.ofMillis(Math.max(1, keepaliveMs)))
                .map(i -> StreamEvent.ping(new StreamMeta("", "", "", "chat", null, false)))
                .takeUntilOther(shared.then().onErrorComplete());
        AtomicBoolean completed = new AtomicBoolean(false);
        // M7-8：响应一旦不可用（客户端断开），后续任何 complete/completeWithError
        // 都会被 Spring 转成 DeferredResult error 并触发全局异常处理器，必须跳过
        AtomicBoolean responseUnusable = new AtomicBoolean(false);
        Runnable finish = () -> {
            if (completed.compareAndSet(false, true)) {
                if (!responseUnusable.get()) {
                    try {
                        emitter.complete();
                    } catch (Exception e) {
                        // 兜底：complete() 仍可能因底层响应损坏抛异常
                        log.debug("[SseStream] 响应已不可用，跳过 complete: {}", e.getMessage());
                    }
                }
            }
        };
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        Disposable disposable = Flux.merge(shared, heartbeats)
                .subscribe(
                        ev -> {
                            if (!send(emitter, ev)) {
                                // M6-53：客户端断开导致发送失败——正常结束推送，
                                // 不触发 error 处理链（否则容器 async error 会尝试
                                // 把 R 写入 text/event-stream 响应产生噪音）；
                                // 底层业务（行程生成）已与订阅取消解耦，继续完成
                                // M7-8 修正：此时不能再 complete()——Spring 的
                                // DefaultSseEmitterHandler.complete() 会把 flush 的
                                // IOException 转成 DeferredResult error 并主动触发
                                // 全局异常处理器；只标记不可用并停止推送即可
                                responseUnusable.set(true);
                                finish.run();
                                Disposable d = disposableRef.get();
                                if (d != null) {
                                    d.dispose();
                                }
                            }
                        },
                        err -> {
                            log.warn("[SseStream] 流式订阅异常: {}", err.getMessage());
                            if (completed.compareAndSet(false, true)) {
                                if (!responseUnusable.get()) {
                                    emitter.completeWithError(err);
                                }
                            }
                        },
                        finish
                );
        disposableRef.set(disposable);
        emitter.onCompletion(() -> {
            disposable.dispose();
            if (onCancel != null) {
                onCancel.run();
            }
        });
        emitter.onTimeout(() -> {
            disposable.dispose();
            if (onCancel != null) {
                onCancel.run();
            }
            finish.run();
        });
        return emitter;
    }

    private boolean send(SseEmitter emitter, StreamEvent event) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(event.type().name().toLowerCase())
                    .reconnectTime(3000L)
                    .data(payloadMapper.toJson(event));
            if (event.eventId() != null) {
                builder.id(event.eventId());
            }
            emitter.send(builder);
            return true;
        } catch (Exception e) {
            // M6-53：客户端断开/超时——返回 false 由订阅方正常收尾
            log.warn("[SseStream] SSE 发送失败（客户端断开）: {}", e.getMessage());
            return false;
        }
    }
}
