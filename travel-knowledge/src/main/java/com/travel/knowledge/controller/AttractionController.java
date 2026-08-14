package com.travel.knowledge.controller;

import com.travel.common.entity.Attraction;
import com.travel.common.result.PageResult;
import com.travel.common.result.R;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.service.AttractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
     * 检索请求体
     */
    public record SearchRequest(String query, String ragType, int topK) {
        public SearchRequest {
            // F40/P1：ragType 缺省（null）走 auto 启发式路由。
            if (topK <= 0) topK = 10;
        }
    }
}
