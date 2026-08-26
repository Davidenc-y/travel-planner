package com.travel.planning.memory.knowledge;

/**
 * 会话知识切片（Phase C/F78，C1 写入侧结构化子块）。
 *
 * @param sessionId  会话命名空间
 * @param type       constraint / feedback / itinerary_day / reference
 * @param seq        稳定序号（itinerary 用 itin:{id}:{day}；chat 由写入器按内容哈希生成）
 * @param content    切片内容
 * @param role       user / assistant
 * @param sourceNode chat / route / attraction_filter
 */
public record SessionChunk(String sessionId, String type, String seq,
                           String content, String role, String sourceNode) {
}
