package com.travel.core.stream;

import reactor.core.publisher.Flux;

/**
 * M6：领域流式入口（框架唯一扩展点）。
 *
 * <p>实现约定：{@code preflight} 只做同步校验，不执行流水线；
 * {@code stream} 必须复用 preflight 的 context，避免幂等门禁重复执行。</p>
 */
public interface StreamingPipeline {

    /**
     * 同步门禁：auth/归属/幂等/状态校验等。
     */
    StreamPreflight preflight(StreamRequest request);

    /**
     * 异步流式主体。
     *
     * @param preflight 必须为 {@code preflight(request)} 返回的 ok 结果
     */
    Flux<StreamEvent> stream(StreamRequest request, StreamPreflight preflight);
}
