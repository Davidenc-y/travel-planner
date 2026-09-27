package com.travel.planning.memory.knowledge;

/**
 * KnowledgeRetrievalService 公共契约（AD-2b 接口化）。
 *
 * <p>实现见 {@link KnowledgeRetrievalServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface KnowledgeRetrievalService {

    /** RAG 检索候选 JSON（query→检索/降级路径，retrieve 主入口）。 */
    String retrieveCandidates(String query, int topK);
}
