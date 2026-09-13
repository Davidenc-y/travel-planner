package com.travel.planning.client;

import com.travel.common.result.R;

import java.util.List;
import java.util.Map;

/**
 * MI-5：Knowledge 检索业务端口（纯签名零注解；传输契约留在 KnowledgeClient Feign 接口）。
 *
 * <p>方法签名 = KnowledgeClient 公共面（2026-09-13 grep 核验，方案 §四 MI-5 修订版）。
 * 三调用方经本端口注入（Spring 按类型解析同一 Feign 代理，行为零变更）；
 * knowledge 微服务抽取时提供同契约实现即可替换传输层。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface KnowledgeSearchPort {

    /**
     * RAG 检索（knowledge /api/v1/rag/search）。
     */
    R<List<Map<String, Object>>> search(String ragType, String query, int topK);

    /**
     * Phase C/F78：写入一条会话知识切片（knowledge /api/v1/memory/session-context）。
     */
    R<Object> writeSessionContext(Map<String, Object> chunk);

    /**
     * Phase C/F78：检索会话知识（sessionId 过滤 + Hybrid RRF）。
     */
    R<List<Map<String, Object>>> searchSessionContext(String sessionId, String query, int topK);

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父，itinerary_day 完整天块视图）。
     */
    R<List<Map<String, Object>>> findSessionContextByPrefix(String sessionId, String seqPrefix, int limit);

    /**
     * M8-9：按 seq 前缀删除会话切片（REFINE/重生成覆盖旧版本）。
     */
    R<Integer> deleteSessionContextByPrefix(String sessionId, String seqPrefix);
}
