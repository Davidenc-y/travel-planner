package com.travel.knowledge.rag.model;

import java.util.List;

/**
 * RAG 策略工具的入参（F42/P2）。
 *
 * <p>Agent 路由时由 LLM 按此结构生成工具调用参数；
 * 工具据此重建 {@link QueryIntent} 并执行对应策略。</p>
 *
 * @param query    查询文本
 * @param city     城市（可空）
 * @param type     景点类型（可空）
 * @param freeOnly 是否要求免费
 * @param keywords 有价值检索词（F46：透传 QueryIntent，保证 agent 路径意图快照一致）
 * @param topK     返回结果数
 */
public record RagToolRequest(
        String query,
        String city,
        String type,
        boolean freeOnly,
        List<String> keywords,
        int topK
) {
}
