package com.travel.knowledge.rag.service;

import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.config.QueryUnderstandingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询理解服务（F40/P1）。
 *
 * <p>前置理解层：LLM 将自由文本查询抽取为结构化 {@link QueryIntent}；
 * LLM 失败/输出非法时回退本地启发式（城市/类型/免费关键词检测），保证链路可用。</p>
 */
@Slf4j
@Service
public class QueryUnderstandingService {

    private final ChatModel chatModel;
    private final QueryUnderstandingProperties properties;
    /** 意图 LRU 缓存（access-order，容量由配置 cacheSize 控制） */
    private final Map<String, QueryIntent> cache;

    public QueryUnderstandingService(ChatModel chatModel, QueryUnderstandingProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, QueryIntent> eldest) {
                return properties.getCacheSize() > 0 && size() > properties.getCacheSize();
            }
        });
    }

    /**
     * 查询理解入口：优先 LLM 抽取（可配置开关），失败回退启发式；结果 LRU 缓存
     */
    public QueryIntent understand(String query) {
        String q = query == null ? "" : query.trim();
        QueryIntent cached = cache.get(q);
        if (cached != null) {
            return cached;
        }
        QueryIntent result;
        if (properties.isEnabled()) {
            QueryIntent llm = extractByLlm(q);
            result = llm != null ? llm : heuristic(q);
            log.info("[QueryUnderstanding] LLM 抽取: {}", result);
        } else {
            result = heuristic(q);
            log.info("[QueryUnderstanding] LLM 已禁用，使用启发式: {}", result);
        }
        if (properties.getCacheSize() > 0) {
            cache.put(q, result);
        }
        return result;
    }

    /**
     * LLM 结构化抽取；返回 null 表示失败（走启发式兜底）
     */
    private QueryIntent extractByLlm(String query) {
        try {
            String prompt = """
                    你是旅游查询理解器。从用户查询中抽取结构化意图，只输出 JSON，不要任何解释或代码块标记。
                    用户查询：%s
                    输出格式：{"city": "城市名或null", "type": "类型或null", "keywords": ["关键词"], "freeOnly": true或false}
                    约束：
                    - city 只填中国城市名（如 北京、上海、西安），未提到填 null
                    - type 只从 CULTURE/NATURE/FOOD/SHOPPING/FAMILY/LEISURE 中选一个，未明确填 null
                    - keywords 抽取 1~5 个有价值检索词，剔除口语与语气词
                    - freeOnly：含"免费/免票/不花钱"等含义为 true，否则 false
                    """.formatted(query);
            String response = chatModel.call(prompt);
            String json = extractJson(response);
            if (json == null) {
                return null;
            }
            IntentRaw raw = JsonUtils.fromJson(json, IntentRaw.class);
            if (raw == null) {
                return null;
            }
            return new QueryIntent(
                    normalizeCity(raw.city()),
                    normalizeType(raw.type()),
                    normalizeKeywords(raw.keywords()),
                    Boolean.TRUE.equals(raw.freeOnly()),
                    query);
        } catch (Exception e) {
            log.warn("[QueryUnderstanding] LLM 抽取失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 启发式兜底（不依赖 LLM）
     */
    private QueryIntent heuristic(String query) {
        return new QueryIntent(
                detectCity(query),
                detectType(query),
                List.of(),
                containsAny(query, "免费", "免票", "不花钱", "无门票"),
                query);
    }

    private String detectCity(String query) {
        for (String city : properties.getCities()) {
            if (query.contains(city)) {
                return city;
            }
        }
        return null;
    }

    private String detectType(String query) {
        for (Map.Entry<String, List<String>> entry : properties.getTypeKeywords().entrySet()) {
            if (containsAny(query, entry.getValue().toArray(new String[0]))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean containsAny(String text, String... tokens) {
        for (String t : tokens) {
            if (text.contains(t)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeCity(String city) {
        if (city == null || city.isBlank() || "null".equalsIgnoreCase(city) || "无".equals(city)) {
            return null;
        }
        return city.trim();
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank() || "null".equalsIgnoreCase(type) || "无".equals(type)) {
            return null;
        }
        String t = type.trim().toUpperCase();
        if (t.matches("CULTURE|NATURE|FOOD|SHOPPING|FAMILY|LEISURE")) {
            return t;
        }
        return null;
    }

    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null) {
            return List.of();
        }
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .toList();
    }

    /**
     * 从 LLM 响应中提取 JSON（容忍 ```json 代码块与前后噪声）
     */
    private String extractJson(String response) {
        if (response == null) {
            return null;
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return response.substring(start, end + 1);
    }

    /** LLM JSON 反序列化中间对象 */
    private record IntentRaw(String city, String type, List<String> keywords, Boolean freeOnly) {
    }
}
