package com.travel.knowledge.rag.service;

import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.config.QueryUnderstandingProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.ArrayList;
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

    // M7 Batch 4：高频短输出 → light 角色（注册表默认 qwen-turbo；RAG 评测硬门禁守护质量）
    public QueryUnderstandingService(@Qualifier("lightModel") ChatModel chatModel,
                                     QueryUnderstandingProperties properties) {
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
                     - type 只反映用户对景点类型的意图（文化/自然/美食/购物/亲子游乐/休闲）；
                       出行人员（家庭/情侣）、预算、天数、开始日期等行程参数不要推断为 type
                     - 若用户同时表达多个景点类型（如"美食+购物"），type 填 null，避免单类型误过滤
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
                    validateLlmType(raw.type(), query),
                    normalizeKeywords(raw.keywords(), query),
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
        String matched = null;
        for (Map.Entry<String, List<String>> entry : properties.getTypeKeywords().entrySet()) {
            if (containsAny(query, entry.getValue().toArray(new String[0]))) {
                if (matched != null) {
                    // F74：多类型并存（如 美食+购物）→ 不按单一类型过滤，交给 BM25/KNN 语义匹配
                    return null;
                }
                matched = entry.getKey();
            }
        }
        return matched;
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

    /**
     * M7-8：LLM 抽取的 type 必须被原始查询中的类型词支撑，否则置 null。
     *
     * <p>背景：qwen-turbo 曾把“帮我规划重庆一日游”抽成 type=FOOD/keywords=[重庆, 美食]，
     * 该幻觉会进入 RagFilterBuilder 的 type 过滤，导致本可命中的城市景点检索为空。
     * 校验通过配置 typeKeywords + 同义词表判定；查询明确表达类型时不受影响。</p>
     */
    private String validateLlmType(String type, String query) {
        String t = normalizeType(type);
        if (t == null || query == null || query.isBlank()) {
            return t;
        }
        List<String> triggers = new ArrayList<>(
                properties.getTypeKeywords().getOrDefault(t, List.of()));
        // M8-2：同义词表并入配置单源（默认值与迁移前逐字一致，行为等价）
        triggers.addAll(properties.getTypeSynonyms().getOrDefault(t, List.of()));
        if (!containsAny(query, triggers.toArray(new String[0]))) {
            log.debug("[QueryUnderstanding] LLM 推断 type={} 但原始查询无对应关键词，置为 null: query={}",
                    t, query);
            return null;
        }
        // M8-7：查询同时表达多个类型（如“文化+美食”“美食和购物”）→ 置 null。
        // 背景：真实冒烟中“帮我规划成都3日游，预算3000元，喜欢文化和美食”被 LLM 抽成
        // 单一 CULTURE，type 过滤后成都候选仅剩 1 条，下游路线只能靠模型自身知识补景点
        // （武侯祠/杜甫草堂等不在候选集）。heuristic 路径已有多类型置 null 规则，
        // LLM 路径补上同一确定性规则（prompt 虽要求 LLM 输出 null，但校验不能依赖 LLM 自觉）。
        int matchedTypes = 0;
        for (String typeKey : properties.getTypeKeywords().keySet()) {
            List<String> words = new ArrayList<>(
                    properties.getTypeKeywords().getOrDefault(typeKey, List.of()));
            words.addAll(properties.getTypeSynonyms().getOrDefault(typeKey, List.of()));
            if (containsAny(query, words.toArray(new String[0]))) {
                matchedTypes++;
            }
        }
        if (matchedTypes > 1) {
            log.debug("[QueryUnderstanding] 查询命中 {} 个类型类别，LLM 单类型 {} 置为 null: query={}",
                    matchedTypes, t, query);
            return null;
        }
        return t;
    }

    /**
     * M7-8：关键词清洗——只保留“能在原始查询文本中找到”的词，并去重。
     *
     * <p>背景：qwen-turbo 曾把“帮我规划重庆一日游”抽成 keywords=[重庆, 美食]，
     * type 已由 {@link #validateLlmType} 置 null，但 keywords 中幻觉词仍会污染
     * 日志与后续可能的关键词检索；此处按原文锚定过滤，保证意图数据干净。</p>
     */
    private List<String> normalizeKeywords(List<String> keywords, String query) {
        if (keywords == null) {
            return List.of();
        }
        String q = query == null ? "" : query.trim();
        return keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .filter(k -> !q.isEmpty() && q.contains(k))
                .distinct()
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
