package com.travel.planning.client;

import com.travel.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Knowledge 模块 Feign 客户端
 *
 * <p>供 travel-planning 模块通过 HTTP 调用 travel-knowledge 的 RAG 检索 API。</p>
 *
 * <p>配置：travel.knowledge.base-url（application.yml，默认 http://localhost:8082）</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@FeignClient(name = "travel-knowledge", url = "${travel.knowledge.base-url:http://localhost:8082}")
public interface KnowledgeClient {

    /**
     * RAG 检索（调用 knowledge 的 /api/v1/rag/search）
     *
     * @param ragType 策略类型（naive/hybrid/self_rag/corrective_rag）
     * @param query   查询文本
     * @param topK    返回结果数
     * @return R<List<Map>> 检索结果（docId/title/snippet/score/...）
     */
    @GetMapping("/api/v1/rag/search")
    R<List<Map<String, Object>>> search(
            @RequestParam("ragType") String ragType,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /**
     * Phase C/F78：写入一条会话知识切片（knowledge /api/v1/memory/session-context）
     */
    @PostMapping("/api/v1/memory/session-context")
    R<Object> writeSessionContext(@RequestBody Map<String, Object> chunk);

    /**
     * Phase C/F78：检索会话知识（sessionId 过滤 + Hybrid RRF）
     */
    @GetMapping("/api/v1/memory/session-context/search")
    R<List<Map<String, Object>>> searchSessionContext(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("query") String query,
            @RequestParam("topK") int topK);

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父，itinerary_day 完整天块视图）。
     * sessionId 隔离与 search 同口径（knowledge 侧 term 过滤）。
     */
    @GetMapping("/api/v1/memory/session-context/by-prefix")
    R<List<Map<String, Object>>> findSessionContextByPrefix(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("seqPrefix") String seqPrefix,
            @RequestParam("limit") int limit);
}
