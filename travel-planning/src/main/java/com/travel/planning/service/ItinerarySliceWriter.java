package com.travel.planning.service;

import com.travel.planning.memory.knowledge.SessionContextChunker;
import com.travel.planning.memory.knowledge.SessionKnowledgeWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * M10-1c：行程 itinerary_day 切片写入器（自 ItineraryService 拆出）。
 *
 * <p>职责：生成/resume 成功后按行程 id 前缀删除旧版本天块，再异步写入新切片；
 * 失败仅 WARN，不阻断主流程（M8-9 语义原样保留）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItinerarySliceWriter {

    private final SessionContextChunker sessionContextChunker;
    private final SessionKnowledgeWriter sessionKnowledgeWriter;

    /**
     * 写会话知识天块（sessionId 为空/行程 JSON 为空时直通）。
     *
     * @param sessionId     会话 ID（可为 null）
     * @param itineraryId   行程 ID（删除前缀用）
     * @param itineraryJson 最终 itinerary JSON（含 routePlan）
     */
    public void writeAfterGenerated(String sessionId, Long itineraryId, String itineraryJson) {
        if (sessionId == null || sessionId.isBlank()
                || itineraryId == null || itineraryJson == null || itineraryJson.isBlank()) {
            return;
        }
        try {
            // M8-9：先按行程 id 前缀删除旧版本（resume/重生成覆盖，避免新旧天块混叠）
            sessionKnowledgeWriter.deleteBySeqPrefix(sessionId, "itin:" + itineraryId + ":");
            sessionKnowledgeWriter.writeAsync(sessionId,
                    sessionContextChunker.chunkItinerary(sessionId, itineraryJson, itineraryId));
        } catch (Exception e) {
            log.warn("[ItinerarySliceWriter] 切片写入失败（不影响主流程）: itineraryId={}, error={}",
                    itineraryId, e.getMessage());
        }
    }
}
