package com.travel.core.stream;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

/**
 * M6：流式模板基类——统一指标埋点、requestId 与 error 兜底事件。
 */
public abstract class AbstractStreamingPipeline implements StreamingPipeline {

    protected final StreamMetrics metrics;

    protected AbstractStreamingPipeline(StreamMetrics metrics) {
        this.metrics = metrics == null ? NoopStreamMetrics.INSTANCE : metrics;
    }

    @Override
    public final Flux<StreamEvent> stream(StreamRequest request, StreamPreflight preflight) {
        long start = System.nanoTime();
        metrics.started(request.domain());
        return Flux.defer(() -> doStream(request, preflight))
                .doOnComplete(() -> metrics.completed(request.domain(), elapsedMs(start)))
                .doOnError(e -> metrics.failed(request.domain(), e, elapsedMs(start)))
                .onErrorResume(e -> Flux.just(errorEvent(request, e)));
    }

    /**
     * 子类实现实际流式逻辑。
     */
    protected abstract Flux<StreamEvent> doStream(StreamRequest request, StreamPreflight preflight);

    protected String newRequestId() {
        return UUID.randomUUID().toString();
    }

    protected StreamEvent errorEvent(StreamRequest request, Throwable error) {
        StreamMeta meta = new StreamMeta(newRequestId(), request.sessionId(),
                request.clientMessageId(), request.domain(), 50000, false);
        String message = error == null || error.getMessage() == null
                ? "流式处理失败" : error.getMessage();
        return StreamEvent.error(meta, 50000, message);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
