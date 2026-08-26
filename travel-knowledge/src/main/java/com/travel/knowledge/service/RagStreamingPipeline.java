package com.travel.knowledge.service;

import com.travel.core.stream.AbstractStreamingPipeline;
import com.travel.core.stream.NoopStreamMetrics;
import com.travel.core.stream.StreamEvent;
import com.travel.core.stream.StreamMeta;
import com.travel.core.stream.StreamMetrics;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.core.stream.StreamingChunker;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.stream.RagStreamProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * M6-19：RAG 检索流式 Pipeline（Item 4 多域流式）。
 */
@Component
public class RagStreamingPipeline extends AbstractStreamingPipeline {

    private final AttractionService attractionService;
    private final RagStreamProperties props;

    @Autowired
    public RagStreamingPipeline(AttractionService attractionService,
                                RagStreamProperties props,
                                ObjectProvider<StreamMetrics> metricsProvider) {
        this(attractionService, props,
                metricsProvider.getIfAvailable(() -> NoopStreamMetrics.INSTANCE));
    }

    RagStreamingPipeline(AttractionService attractionService,
                         RagStreamProperties props,
                         StreamMetrics metrics) {
        super(metrics);
        this.attractionService = attractionService;
        this.props = props;
    }

    @Override
    public StreamPreflight preflight(StreamRequest request) {
        String query = request.input();
        if (query == null || query.isBlank()) {
            return StreamPreflight.fail(40001, "检索关键词不能为空");
        }
        return StreamPreflight.ok(query);
    }

    @Override
    protected Flux<StreamEvent> doStream(StreamRequest request, StreamPreflight preflight) {
        String query = (String) preflight.context();
        String ragType = request.attributes().get("ragType") == null
                ? "hybrid" : String.valueOf(request.attributes().get("ragType"));
        int topK = request.attributes().get("topK") == null
                ? 10 : Integer.parseInt(String.valueOf(request.attributes().get("topK")));
        StreamMeta meta = new StreamMeta(newRequestId(), null, null, "rag", null, false);
        return Flux.<StreamEvent>create(sink -> {
            try {
                sink.next(StreamEvent.thinking(meta, "searching", "正在检索知识库…"));
                List<SearchResult> results = attractionService.search(query, ragType, topK);
                AtomicInteger seq = new AtomicInteger(0);
                if (results.isEmpty()) {
                    sink.next(StreamEvent.thinking(meta, "empty", "未检索到相关结果"));
                } else {
                    String text = buildText(results);
                    for (String chunk : StreamingChunker.chunk(text, props.getChunkMaxChars())) {
                        sink.next(StreamEvent.token(meta, chunk,
                                String.valueOf(seq.incrementAndGet())));
                    }
                }
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("count", results.size());
                done.put("ragType", ragType);
                sink.next(StreamEvent.done(meta, done, String.valueOf(seq.get() + 1)));
                sink.complete();
            } catch (Exception e) {
                sink.next(StreamEvent.error(meta, 50000,
                        e.getMessage() == null ? "检索失败" : e.getMessage()));
                sink.complete();
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String buildText(List<SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        for (SearchResult r : results) {
            if (r.getTitle() != null) {
                sb.append("【").append(r.getTitle()).append("】");
            }
            if (r.getSource() != null) {
                sb.append("（").append(r.getSource());
                if (r.getScore() > 0) {
                    sb.append(" · ").append(String.format("%.4f", r.getScore()));
                }
                sb.append("）");
            }
            sb.append("\n");
            if (r.getSnippet() != null) {
                sb.append(r.getSnippet()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
