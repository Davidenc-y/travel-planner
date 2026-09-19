package com.travel.knowledge.controller;

import com.travel.common.result.R;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.service.AttractionService;
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

    private final AttractionService attractionService;

    /**
     * RAG 检索
     *
     * @param ragType 策略类型（naive/hybrid/self_rag/corrective_rag），缺省走 auto 路由
     * @param query   查询文本
     * @param topK    返回结果数（默认 10）
     * @return 检索结果列表
     */
    @GetMapping("/search")
    public R<List<SearchResult>> search(
            @RequestParam(required = false) String ragType,
            @RequestParam String query,
            @RequestParam(required = false, defaultValue = "10") int topK) {
        // G-2：输入护栏——空/超长/控制字符统一友好拒绝（不暴露内部细节，与前端 input-guard 对齐）
        if (query == null || query.isBlank()) {
            return R.fail(40001, "查询内容为空");
        }
        String trimmed = query.trim();
        if (trimmed.length() > 500) {
            return R.fail(40001, "查询内容过长（≤500字），请精简后重试");
        }
        if (trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) {
            return R.fail(40001, "查询内容包含非法控制字符");
        }
        // topK 范围护栏
        if (topK < 1 || topK > 50) {
            topK = Math.max(1, Math.min(topK, 50));
        }
        log.info("[RagController] ragType={}, query={}, topK={}", ragType, trimmed, topK);
        // M3-2/P0-7：统一委托 AttractionService.search（保证追溯链路一致，不再双入口）
        String type = (ragType == null || ragType.isBlank()) ? "hybrid" : ragType;
        return R.ok(attractionService.search(trimmed, type, topK));
    }
}
