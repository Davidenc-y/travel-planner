package com.travel.knowledge.controller;

import com.travel.common.result.R;
import com.travel.knowledge.rag.RagDispatcher;
import com.travel.knowledge.rag.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * RAG 检索接口
 *
 * <p>供 travel-planning 模块通过 HTTP/Feign 调用的 RAG 检索端点。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/rag")
@RequiredArgsConstructor
public class RagController {

    private final RagDispatcher ragDispatcher;

    /**
     * RAG 检索
     *
     * @param ragType 策略类型（naive/hybrid/self_rag/corrective_rag），默认 hybrid
     * @param query   查询文本
     * @param topK    返回结果数（默认 10）
     * @return 检索结果列表
     */
    @GetMapping("/search")
    public R<List<SearchResult>> search(
            @RequestParam(required = false, defaultValue = "hybrid") String ragType,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "10") int topK) {
        log.info("[RagController] ragType={}, query={}, topK={}", ragType, query, topK);
        return R.ok(ragDispatcher.dispatch(ragType, query, topK));
    }

    /**
     * 获取已注册的 RAG 策略列表
     */
    @GetMapping("/strategies")
    public R<Object> getStrategies() {
        return R.ok(ragDispatcher.getStrategies().keySet());
    }
}
