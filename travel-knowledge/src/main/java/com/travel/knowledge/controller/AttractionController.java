package com.travel.knowledge.controller;

import com.travel.common.entity.Attraction;
import com.travel.common.result.PageResult;
import com.travel.common.result.R;
import com.travel.core.stream.StreamPreflight;
import com.travel.core.stream.StreamRequest;
import com.travel.knowledge.service.RagStreamingPipeline;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.service.AttractionService;
import com.travel.knowledge.stream.RagStreamProperties;
import com.travel.planning.stream.StreamErrorMapper;
import com.travel.webmvc.stream.SseStreamAdapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * 景点接口
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/attractions")
@RequiredArgsConstructor
public class AttractionController {

    private final AttractionService attractionService;
    private final RagStreamingPipeline ragStreamingPipeline;
    private final SseStreamAdapter sseStreamAdapter;
    private final RagStreamProperties ragStreamProps;

    /**
     * 分页查询景点
     */
    @GetMapping
    public R<PageResult<Attraction>> list(
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return R.ok(attractionService.list(city, type, page, size));
    }

    /**
     * M5-1：全部城市列表（“浏览全部”下拉动态数据源）
     */
    @GetMapping("/cities")
    public R<List<String>> listCities() {
        return R.ok(attractionService.listCities());
    }

    /**
     * 查询景点详情
     */
    @GetMapping("/{id}")
    public R<Attraction> getById(@PathVariable Long id) {
        return R.ok(attractionService.getById(id));
    }

    /**
     * RAG 景点检索
     */
    @PostMapping("/search")
    public R<List<SearchResult>> search(@RequestBody SearchRequest req) {
        log.info("景点检索: query={}, ragType={}, topK={}", req.query(), req.ragType(), req.topK());
        return R.ok(attractionService.search(req.query(), req.ragType(), req.topK()));
    }

    /**
     * M6-19：RAG 检索流式（SSE）。成功直接返回 SseEmitter。
     */
    @PostMapping("/search/stream")
    public Object searchStream(@RequestBody SearchRequest req) {
        if (!ragStreamProps.isEnabled()) {
            return ResponseEntity.notFound().build();
        }
        String ragType = req.ragType() == null || req.ragType().isBlank() ? "hybrid" : req.ragType();
        int topK = req.topK() <= 0 ? 10 : req.topK();
        StreamRequest request = new StreamRequest("rag", null, null, req.query(), null,
                Map.of("ragType", ragType, "topK", topK), null);
        StreamPreflight pre = ragStreamingPipeline.preflight(request);
        if (!pre.ok()) {
            return ResponseEntity.status(StreamErrorMapper.httpStatus(pre.code()))
                    .body(R.fail(pre.code(), pre.message()));
        }
        SseEmitter emitter = sseStreamAdapter.toEmitter(
                ragStreamingPipeline.stream(request, pre),
                ragStreamProps.getTimeoutMs(),
                ragStreamProps.getKeepaliveMs());
        return emitter;
    }

    /**
     * 检索请求体
     */
    public record SearchRequest(String query, String ragType, int topK) {
        public SearchRequest {
            // F40/P1：ragType 缺省（null）走 auto 启发式路由。
            if (topK <= 0) topK = 10;
        }
    }
}
