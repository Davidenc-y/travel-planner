package com.travel.planning.memory.knowledge;

import com.travel.common.util.JsonUtils;
import com.travel.planning.client.KnowledgeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库预检索服务（F63）。
 *
 * <p>在聊天/行程输入组合前确定性调用 knowledge RAG，把候选景点注入上下文，
 * 不再依赖 LLM 自觉调用工具（TC-10 实测 attraction_filter 多次跳过工具）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalService {

    private static final int SNIPPET_MAX = 80;

    private final KnowledgeClient knowledgeClient;

    /**
     * 检索候选景点并返回紧凑 JSON 数组（docId/title/snippet，snippet 截断）；
     * 失败/空返回 "[]"，不阻断流程。
     */
    public String retrieveCandidates(String query, int topK) {
        try {
            var resp = knowledgeClient.search("hybrid", query, Math.max(1, Math.min(topK, 10)));
            if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
                log.warn("[KnowledgeRetrieval] 检索为空: query={}", query);
                return "[]";
            }
            List<Map<String, Object>> compact = new ArrayList<>();
            for (Map<String, Object> item : resp.getData()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("docId", item.get("docId"));
                c.put("title", item.get("title"));
                String snippet = item.get("snippet") == null ? "" : item.get("snippet").toString();
                c.put("snippet", snippet.length() > SNIPPET_MAX ? snippet.substring(0, SNIPPET_MAX) + "…" : snippet);
                compact.add(c);
            }
            log.info("[KnowledgeRetrieval] 候选景点 {} 条: query={}", compact.size(), query);
            return JsonUtils.toJson(compact);
        } catch (Exception e) {
            log.warn("[KnowledgeRetrieval] 检索失败，降级空候选: {}", e.getMessage());
            return "[]";
        }
    }
}
