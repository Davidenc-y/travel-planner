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
}
