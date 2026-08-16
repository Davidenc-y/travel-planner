package com.travel.knowledge.memory;

/**
 * 会话级知识切片（Phase C/F78，C2 写入口 DTO）。
 *
 * <p>由 planning 侧（写入侧）按 F49 第九节结构化切好后提交；knowledge 仅负责
 * 规范化 + 向量化 + 双写（Milvus session_context + ES session_context）。</p>
 *
 * @param chunkId    幂等键：sessionId:type:contentHash
 * @param sessionId  会话命名空间
 * @param type       constraint / feedback / itinerary_day / reference
 * @param seq        稳定序号（内容哈希前缀 或 itin:{id}:{day}）
 * @param content    切片内容
 * @param role       user / assistant
 * @param sourceNode chat / route / attraction_filter
 * @param createdAt  ISO 时间
 */
public record SessionContextChunk(
        String chunkId,
        String sessionId,
        String type,
        String seq,
        String content,
        String role,
        String sourceNode,
        String createdAt) {
}
