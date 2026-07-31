package com.travel.knowledge.rag;

import java.util.List;

/**
 * RAG 检索策略接口
 *
 * <p>4 种实现：Naive / Hybrid / SelfRAG / CorrectiveRAG</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public interface RagStrategy {

    /**
     * 执行检索
     *
     * @param query 用户查询
     * @param topK  返回结果数
     * @return 检索结果列表
     */
    List<SearchResult> retrieve(String query, int topK);

    /**
     * 策略类型标识
     *
     * @return 策略名（naive / hybrid / self_rag / corrective_rag）
     */
    String getType();
}
