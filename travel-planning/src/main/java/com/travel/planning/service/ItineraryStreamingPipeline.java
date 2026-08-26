package com.travel.planning.service;

import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.exception.BusinessException;
import com.travel.core.stream.AbstractStreamingPipeline;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import com.travel.core.stream.StreamMetrics;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.core.stream.StreamingChunker;
import com.travel.planning.stream.ItineraryStreamProperties;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M6-15 Item 4：行程流式 Pipeline（复用 B0 框架）。
 *
 * <p>preflight 校验请求；stream 在虚拟线程上执行 {@link ItineraryService#generate}，
 * 输出 thinking → token（行程文本分块）→ done；异常输出 error 事件。</p>
 */
@Component
public class ItineraryStreamingPipeline extends AbstractStreamingPipeline {

    private final ItineraryService itineraryService;
    private final ItineraryStreamProperties props;

    public ItineraryStreamingPipeline(ItineraryService itineraryService,
                                      ItineraryStreamProperties props,
                                      StreamMetrics metrics) {
        super(metrics);
        this.itineraryService = itineraryService;
        this.props = props;
    }

    @Override
    public StreamPreflight preflight(StreamRequest request) {
        ItineraryGenerateRequestDTO req =
                (ItineraryGenerateRequestDTO) request.attributes().get("req");
        if (req == null) {
            return StreamPreflight.fail(40001, "请求体缺失");
        }
        if (req.getDestination() == null || req.getDestination().isBlank()) {
            return StreamPreflight.fail(40001, "目的地不能为空");
        }
        if (req.getDays() == null || req.getDays() < 1 || req.getDays() > 30) {
            return StreamPreflight.fail(40001, "天数须在 1-30 之间");
        }
        if (req.getClientRequestId() == null || req.getClientRequestId().isBlank()) {
            return StreamPreflight.fail(40001, "clientRequestId 不能为空");
        }
        return StreamPreflight.ok(req);
    }

    @Override
    protected Flux<StreamEvent> doStream(StreamRequest request, StreamPreflight preflight) {
        ItineraryGenerateRequestDTO req =
                (ItineraryGenerateRequestDTO) preflight.context();
        Long userId = request.userId();
        StreamMeta meta = new StreamMeta(newRequestId(), null, null, "itinerary", null, false);
        return Flux.<StreamEvent>create(sink -> {
            // M6-53：generate 在独立虚拟线程执行，与 SSE 订阅取消解耦——
            // 客户端刷新/断开只停止推送，不中断生成（否则工作流被中断置 FAILED，
            // 实测：SSE 发送失败 → Reactor 取消 → boundedElastic 线程中断 →
            // future.get() 抛 InterruptedException → "行程生成被中断"）
            Thread.ofVirtual().name("itinerary-generate-stream").start(() -> {
                try {
                    sink.next(StreamEvent.thinking(meta, "preparing", "正在准备行程生成…"));
                    ItineraryResponseDTO result = itineraryService.generate(req, userId);
                    sink.next(StreamEvent.thinking(meta, "finalizing", "正在整理行程…"));
                    String text = ItineraryStreamTextBuilder.build(result);
                    AtomicInteger seq = new AtomicInteger(0);
                    for (String chunk : StreamingChunker.chunk(text, props.getChunkMaxChars())) {
                        sink.next(StreamEvent.token(meta, chunk,
                                String.valueOf(seq.incrementAndGet())));
                    }
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("itineraryId", result.getId());
                    done.put("status", result.getStatus());
                    done.put("destination", result.getDestination());
                    done.put("days", result.getDays());
                    if (result.getEstimatedCost() != null) {
                        done.put("estimatedCost", result.getEstimatedCost());
                    }
                    sink.next(StreamEvent.done(meta, done, String.valueOf(seq.get() + 1)));
                    sink.complete();
                } catch (Exception e) {
                    sink.next(StreamEvent.error(meta, 50000,
                            e.getMessage() == null ? "行程生成失败" : e.getMessage()));
                    sink.complete();
                }
            });
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER);
    }
}
