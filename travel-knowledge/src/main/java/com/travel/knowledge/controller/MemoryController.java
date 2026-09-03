package com.travel.knowledge.controller;

import com.travel.common.result.R;
import com.travel.knowledge.memory.SessionContextChunk;
import com.travel.knowledge.memory.SessionContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 会话级记忆接口（Phase C/F78，C2）。
 *
 * <p>提供 session_context 切片写入与检索端点，供 travel-planning 通过 Feign 调用：
 * POST /api/v1/memory/session-context、GET /api/v1/memory/session-context/search。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/memory")
@RequiredArgsConstructor
public class MemoryController {

    private final SessionContextService sessionContextService;

    /**
     * 写入一条会话知识切片（planning 侧按结构化类型提交）
     */
    @PostMapping("/session-context")
    public R<Void> writeSessionContext(@RequestBody SessionContextChunk chunk) {
        log.info("[MemoryController] 写入会话切片: chunkId={}, sessionId={}, type={}",
                chunk != null ? chunk.chunkId() : null,
                chunk != null ? chunk.sessionId() : null,
                chunk != null ? chunk.type() : null);
        sessionContextService.write(chunk);
        return R.ok(null);
    }

    /**
     * 检索会话知识（sessionId 过滤 + Hybrid RRF）
     */
    @GetMapping("/session-context/search")
    public R<List<Map<String, Object>>> searchSessionContext(
            @RequestParam String sessionId,
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        log.info("[MemoryController] 检索会话知识: sessionId={}, query={}, topK={}",
                sessionId, query, topK);
        return R.ok(sessionContextService.search(sessionId, query, topK));
    }

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父，itinerary_day 完整天块视图）。
     * sessionId 隔离与 search 同口径；失败降级空列表（调用方保留原命中）。
     */
    @GetMapping("/session-context/by-prefix")
    public R<List<Map<String, Object>>> findSessionContextByPrefix(
            @RequestParam String sessionId,
            @RequestParam String seqPrefix,
            @RequestParam(defaultValue = "30") int limit) {
        log.info("[MemoryController] 按前缀取回会话切片: sessionId={}, seqPrefix={}, limit={}",
                sessionId, seqPrefix, limit);
        return R.ok(sessionContextService.findBySeqPrefix(sessionId, seqPrefix, limit));
    }

    /**
     * M8-9：按 seq 前缀删除会话切片（REFINE/重生成覆盖旧版本）。
     */
    @DeleteMapping("/session-context/by-prefix")
    public R<Integer> deleteSessionContextByPrefix(
            @RequestParam String sessionId,
            @RequestParam String seqPrefix) {
        log.info("[MemoryController] 按前缀删除会话切片: sessionId={}, seqPrefix={}",
                sessionId, seqPrefix);
        return R.ok(sessionContextService.deleteBySeqPrefix(sessionId, seqPrefix));
    }
}
