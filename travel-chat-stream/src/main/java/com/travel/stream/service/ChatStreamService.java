package com.travel.stream.service;

import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import reactor.core.publisher.Flux;

/**
 * ChatStreamService 公共契约（AD-2a 接口化 part2）。
 *
 * <p>实现见 {@link ChatStreamServiceImpl}（@Component 在 Impl，extends AbstractStreamingPipeline）；
 * 公有方法签名逐字提取（零语义变更）。preflight 为 AbstractStreamingPipeline 的覆写点；
 * stream 继承自 AbstractStreamingPipeline（public final），接口声明由继承方法满足。</p>
 */
public interface ChatStreamService {

    /** 流式预检（额度/安全/在途轮次校验，M6 语义）。 */
    StreamPreflight preflight(StreamRequest request);

    /** 流式执行主入口（模板方法：defer + 分块 + 事件化）。 */
    Flux<StreamEvent> stream(StreamRequest request, StreamPreflight preflight);
}
