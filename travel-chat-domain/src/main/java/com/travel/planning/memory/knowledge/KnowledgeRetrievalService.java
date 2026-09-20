package com.travel.planning.memory.knowledge;

import com.travel.common.util.JsonUtils;
import com.travel.planning.client.KnowledgeSearchPort;
import com.travel.planning.trace.TraceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 知识库预检索服务（F63）。
 *
 * <p>在聊天/行程输入组合前确定性调用 knowledge RAG，把候选景点注入上下文，
 * 不再依赖 LLM 自觉调用工具（TC-10 实测 attraction_filter 多次跳过工具）。</p>
 *
 * <p>M8-1：注入格式从「docId/title/80 字 snippet」升级为「结构化事实卡片」——
 * openHours/ticketPrice/recommendedDuration 等结构化字段随候选注入，
 * LLM 报出的门票/开放时间有库内事实可依，不再依赖 snippet 推断或训练数据；
 * snippet 降级为描述性补充（仍截断 SNIPPET_MAX）。null 字段不出现，
 * 语义为「知识库暂无该数据」（与 SearchResult null 语义约定一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int SNIPPET_MAX = 80;

    /** M8-2 数据来源说明段（M18-2 起外置 prompts/knowledge_source_note.st；null 字段=知识库暂无数据） */
    private static final String SOURCE_NOTE =
            com.travel.common.util.PromptFiles.get("knowledge_source_note");

    private final KnowledgeSearchPort knowledgeClient;

    /** RK-1：low-confidence-policy 注入策略（filter=滤除低置信候选；keep=旧全量注入行为） */
    private final RagInjectionPolicy injectionPolicy;

    /** RK-13/D-5：线上质量计数器（abstain/lowconf/degraded，fail-open） */
    private final RagQualityCounters ragQualityCounters;

    /**
     * 检索候选景点并返回紧凑 JSON 数组（结构化事实卡片）；
     * 失败/空返回 "[]"，不阻断流程。
     */
    public String retrieveCandidates(String query, int topK) {
        try {
            // H-1：聊天/行程规划链路改走 auto 自主路由（原先硬编码 hybrid 旁路了三层路由系统）
            var resp = knowledgeClient.search("auto", query, Math.max(1, Math.min(topK, 10)));
            if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
                log.warn("[KnowledgeRetrieval] 检索为空: query={}", query);
                markDegraded("knowledge_empty", query);
                return "[]";
            }
            List<Map<String, Object>> compact = new ArrayList<>();
            int lowConfidenceDropped = 0;
            for (Map<String, Object> item : resp.getData()) {
                // RK-1：filter 策略丢弃 RerankGate 标记的低置信候选（keep=旧全量注入行为）
                if (injectionPolicy.isFilterEnabled() && Boolean.TRUE.equals(item.get("lowConfidence"))) {
                    lowConfidenceDropped++;
                    ragQualityCounters.recordLowConfidenceDropped(1);
                    continue;
                }
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("docId", item.get("docId"));
                c.put("name", item.get("title"));
                // M8-1：结构化事实字段（null 不注入 = 知识库暂无该数据）
                putIfNotNull(c, "city", item.get("city"));
                putIfNotNull(c, "type", item.get("type"));
                putIfNotNull(c, "openHours", item.get("openHours"));
                putIfNotNull(c, "recommendedDuration", item.get("recommendedDuration"));
                putIfNotNull(c, "rating", item.get("rating"));
                putIfNotNull(c, "dataSource", item.get("dataSource"));
                // RK-8：冲突标注透传（null=无冲突不注入）
                putIfNotNull(c, "conflictNote", item.get("conflictNote"));
                // freeEntry=true 显式计 0 元，避免 LLM 对免费景点编造价格
                if (Boolean.TRUE.equals(item.get("freeEntry"))) {
                    c.put("ticketPrice", 0);
                } else {
                    putIfNotNull(c, "ticketPrice", item.get("ticketPrice"));
                }
                String snippet = item.get("snippet") == null ? "" : item.get("snippet").toString();
                c.put("description", snippet.length() > SNIPPET_MAX
                        ? snippet.substring(0, SNIPPET_MAX) + "…" : snippet);
                compact.add(c);
            }
            // RK-1：低置信过滤统计与全滤拒答语义（模板 rag_abstain_note 由 RK-2/A-3 落盘，同批推送）
            if (injectionPolicy.isFilterEnabled() && lowConfidenceDropped > 0) {
                log.info("[RagInjection] lowConfidenceDropped={} kept={}", lowConfidenceDropped, compact.size());
                if (compact.isEmpty()) {
                    // 全部低置信 → 拒答语义：空资料 + 拒答指引，并按既有降级方式记录原因
                    if (TraceContext.active()) {
                        TraceContext.current().degradedReason = "rag_all_low_confidence";
                    }
                    ragQualityCounters.recordAbstain();
                    return com.travel.common.util.PromptFiles.get("rag_abstain_note");
                }
            }
            log.info("[KnowledgeRetrieval] 候选景点 {} 条: query={}", compact.size(), query);
            // M8-2：候选 JSON 前追加数据来源说明（一次改动同时覆盖聊天与行程图注入路径）
            String json = JsonUtils.toJson(compact);
            // M8-4：含 web_enrich 时追加低置信度提示（仅动态拼接，无则零开销）
            boolean hasWebEnrich = compact.stream()
                    .anyMatch(c -> "web_enrich".equals(c.get("dataSource")));
            if (hasWebEnrich) {
                // M18-2：低置信提示外置 prompts/web_enrich_notice.st
                json += com.travel.common.util.PromptFiles.get("web_enrich_notice");
            }
            // RK-8：存在冲突标注时追加冲突呈现段（rag_conflict_note，{conflicts}="名称：提示"逐行）
            String conflicts = compact.stream()
                    .filter(c -> c.get("conflictNote") != null)
                    .map(c -> c.get("name") + "：" + c.get("conflictNote"))
                    .collect(Collectors.joining("\n"));
            if (!conflicts.isEmpty()) {
                json += com.travel.common.util.PromptFiles.get("rag_conflict_note")
                        .replace("{conflicts}", conflicts);
            }
            return SOURCE_NOTE + "\n" + json;
        } catch (Exception e) {
            log.warn("[KnowledgeRetrieval] 检索失败，降级空候选: {}", e.getMessage());
            markDegraded("knowledge_feign_fail", query);
            ragQualityCounters.recordDegraded();
            return "[]";
        }
    }

    /** M8-2：检索降级可观测——chat-domain 侧以 t_agent_trace DEGRADED 状态标注（trace 开启时） */
    private static void markDegraded(String reason, String query) {
        if (TraceContext.active()) {
            TraceContext.current().degradedReason = reason;
        }
        log.warn("[KnowledgeRetrieval] 检索降级事件: reason={}, query={}", reason, query);
    }

    /** 值为 null 时不注入（null = 知识库暂无该数据，prompt 内已约定该语义） */
    private static void putIfNotNull(Map<String, Object> c, String key, Object value) {
        if (value != null) {
            c.put(key, value);
        }
    }
}
