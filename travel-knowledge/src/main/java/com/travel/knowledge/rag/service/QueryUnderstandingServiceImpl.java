package com.travel.knowledge.rag.service;

import com.travel.common.util.JsonUtils;
import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.config.QueryUnderstandingProperties;
import com.travel.knowledge.rag.support.RagRoutingMetrics;
import com.travel.common.trace.SpanCollector;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 查询理解服务（F40/P1）。
 *
 * <p>前置理解层：LLM 将自由文本查询抽取为结构化 {@link QueryIntent}；
 * LLM 失败/输出非法时回退本地启发式（城市/类型/免费关键词检测），保证链路可用。</p>
 */
@Slf4j
@Service
public class QueryUnderstandingServiceImpl implements QueryUnderstandingService {

    private final ChatModel chatModel;
    private final QueryUnderstandingProperties properties;
    /** 意图 LRU 缓存（access-order，容量由配置 cacheSize 控制） */
    private final Map<String, QueryIntent> cache;

    /** S-B5b：Span 采集挂点（optional 注入，缺省自给=未绑定时挂点空安全跳过） */
    private SpanCollector spanCollector = new SpanCollector();

    @Autowired(required = false)
    void setSpanCollector(SpanCollector spanCollector) {
        this.spanCollector = spanCollector;
    }

    /** AJ-1b：QU 缓存命中观测挂点（optional 注入，缺省 null=未绑定时跳过，S-B5b 同款；常开无键） */
    private RagRoutingMetrics ragMetrics;

    @Autowired(required = false)
    void setRagMetrics(RagRoutingMetrics ragMetrics) {
        this.ragMetrics = ragMetrics;
    }

    /** AN-1b：QU LLM 专用执行器（虚拟线程 per-task=方案修正 1②；无界无拒绝面，信号量为唯一并发闸） */
    private static final ExecutorService QU_LLM_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** AN-1a：QU LLM 并发帽信号量（懒建随 maxConcurrentLlm 配置值；0=不限不建不闸） */
    private Semaphore quLlmSlots;
    private int quLlmSlotsCap;

    /** AN-1a：信号量懒建访问器（容量随配置值；0=返回 null=不闸，E-33 现状语义；包级=测试同包直取观测面） */
    Semaphore quLlmSlots() {
        int cap = properties.getMaxConcurrentLlm();
        if (cap <= 0) {
            return null;
        }
        synchronized (this) {
            if (quLlmSlots == null || quLlmSlotsCap != cap) {
                quLlmSlots = new Semaphore(cap);
                quLlmSlotsCap = cap;
            }
            return quLlmSlots;
        }
    }

    // M7 Batch 4：高频短输出 → light 角色（注册表默认 qwen-turbo；RAG 评测硬门禁守护质量）
    public QueryUnderstandingServiceImpl(@Qualifier("lightModel") ChatModel chatModel,
                                     QueryUnderstandingProperties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, QueryIntent> eldest) {
                // AK-3c：驱逐判定逐字保留，判真时 +1 观测（审计段 cacheSize 裁定直接证据）
                boolean evict = properties.getCacheSize() > 0 && size() > properties.getCacheSize();
                if (evict && ragMetrics != null) {
                    ragMetrics.recordQuCacheEvict();
                }
                return evict;
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
            if (ragMetrics != null) {
                ragMetrics.recordQuCacheHit();
            }
            return cached;
        }
        if (ragMetrics != null) {
            ragMetrics.recordQuCacheMiss();
        }
        // S-B5b：查询理解段 span（同线程上下文，未绑定空安全跳过）
        SpanCollector.Span quSpan = spanCollector.start("qu", "understanding");
        QueryIntent result;
        boolean acquired = false;
        try {
            if (properties.isEnabled()) {
                // AN-1a 并发信号量：tryAcquire 失败=fail-open（原始查询走启发式继续检索+计数）——不排队不加延迟
                Semaphore slots = quLlmSlots();
                if (slots != null) {
                    if (!slots.tryAcquire()) {
                        if (ragMetrics != null) {
                            ragMetrics.recordQuOverflow();
                        }
                        log.info("[QueryUnderstanding] QU 并发帽({})已满，fail-open 启发式兜底", properties.getMaxConcurrentLlm());
                        return failOpen(q, quSpan, "overflow");
                    }
                    acquired = true;
                }
                // AN-1b 调用超时：限时 get——超时=cancel(true)+fail-open+计数（虚拟线程阻塞 get 零载体代价）；0=不限现状直调
                if (properties.getLlmTimeoutMs() > 0) {
                    // FutureTask 而非 CompletableFuture：cancel(true) 必须真实打断底层调用线程（skill 四硬规则语义本体）
                    FutureTask<QueryIntent> future = new FutureTask<>(() -> extractByLlm(q));
                    QU_LLM_EXECUTOR.execute(future);
                    try {
                        QueryIntent llm = future.get(properties.getLlmTimeoutMs(), TimeUnit.MILLISECONDS);
                        result = llm != null ? llm : heuristic(q);
                    } catch (TimeoutException te) {
                        future.cancel(true); // 超时路径必须 cancel(true)（skill 四硬规则=P0⑲）
                        if (ragMetrics != null) {
                            ragMetrics.recordQuTimeout();
                        }
                        log.info("[QueryUnderstanding] LLM 抽取超时({}ms)，fail-open 启发式兜底", properties.getLlmTimeoutMs());
                        return failOpen(q, quSpan, "timeout");
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        future.cancel(true);
                        if (ragMetrics != null) {
                            ragMetrics.recordQuTimeout();
                        }
                        log.info("[QueryUnderstanding] LLM 抽取等待被中断，fail-open 启发式兜底");
                        return failOpen(q, quSpan, "interrupted");
                    } catch (ExecutionException ee) {
                        // extractByLlm 自带 catch→null 永不抛；载体面兜底按原异常语义外抛（方案 §2.1）
                        Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                        if (cause instanceof RuntimeException re) {
                            throw re;
                        }
                        throw new IllegalStateException(cause);
                    }
                } else {
                    QueryIntent llm = extractByLlm(q);
                    result = llm != null ? llm : heuristic(q);
                }
                log.info("[QueryUnderstanding] LLM 抽取: {}", result);
            } else {
                result = heuristic(q);
                log.info("[QueryUnderstanding] LLM 已禁用，使用启发式: {}", result);
            }
        } finally {
            if (acquired) {
                Semaphore slots = quLlmSlots();
                if (slots != null) {
                    slots.release();
                }
            }
        }
        if (quSpan != null) {
            Map<String, Object> quAttrs = new LinkedHashMap<>();
            quAttrs.put("llmEnabled", properties.isEnabled());
            quAttrs.put("city", result.city());
            quAttrs.put("type", result.type());
            quAttrs.put("keywords", result.keywords() == null ? 0 : result.keywords().size());
            spanCollector.end(quSpan, "ok", quAttrs);
        }
        if (properties.getCacheSize() > 0) {
            cache.put(q, result);
        }
        return result;
    }

    /**
     * AN-1 fail-open 统一出口：end span（观测面收口，防楔死期 span 泄漏）+返回启发式兜底。
     * 调用方以早退跳过 cache.put——瞬态降级不得被 LRU 固化（方案修正 1④）；
     * 配置态 heuristic 可缓存=既有语义，两态区分。
     */
    private QueryIntent failOpen(String q, SpanCollector.Span quSpan, String reason) {
        QueryIntent fallback = heuristic(q);
        if (quSpan != null) {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("llmEnabled", properties.isEnabled());
            attrs.put("failOpen", reason);
            attrs.put("city", fallback.city());
            attrs.put("type", fallback.type());
            attrs.put("keywords", fallback.keywords() == null ? 0 : fallback.keywords().size());
            spanCollector.end(quSpan, "ok", attrs);
        }
        return fallback;
    }

    /**
     * LLM 结构化抽取；返回 null 表示失败（走启发式兜底）
     */
    private QueryIntent extractByLlm(String query) {
        try {
            // M18-2：prompt 外置 prompts/query_understanding.st（H13）
            String prompt = com.travel.common.util.PromptFiles.get("query_understanding")
                    .formatted(query);
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
            if (containsAnyType(query, entry.getValue().toArray(new String[0]))) {
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

    /**
     * AA-3（记录项清偿·授权行为修正）：type 命中放宽——复合关键词拆词元任一命中。
     *
     * <p>词元=≥3 字词的头/尾 2 字连续子串（如「美食街」→美食+食街、「文化景点」→文化+景点）。
     * 等价性：全词在场=原 contains 语义原样（单关键词行为零变）；仅放宽复合词被间隔/
     * 变体拆开的过紧场景（如「美食 街区」未含整串「美食街」）。freeOnly 免费语义
     * 不走本方法（保持严格 contains，防「花钱」类误判）。</p>
     */
    private boolean containsAnyType(String text, String... tokens) {
        for (String t : tokens) {
            if (t == null || t.isEmpty()) {
                continue;
            }
            if (text.contains(t)) {
                return true;
            }
            if (t.length() >= 3
                    && (text.contains(t.substring(0, 2)) || text.contains(t.substring(t.length() - 2)))) {
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
        if (!containsAnyType(query, triggers.toArray(new String[0]))) {
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
            if (containsAnyType(query, words.toArray(new String[0]))) {
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
