package com.travel.knowledge.rag.support;

import com.travel.knowledge.rag.model.QueryIntent;
import com.travel.knowledge.rag.model.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RAG 查询级进程内 TTL 缓存（B1.2：传输收敛与弹性化批次）
 *
 * <p>单例 @Component：ConcurrentHashMap（键 → 值+写入时间戳），过期清扫在写入时
 * 顺带执行（无后台线程），读路径惰性过期。TTL 默认 60s、上限 256 条（写入超限时
 * 按时间戳截断最旧条目）。</p>
 *
 * <p>开关 travel.rag.query-cache.enabled 默认 true、travel.rag.query-cache.ttl-ms
 * 默认 60000；开关关闭时 get/put 双直通（零缓存行为）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class QueryTtlCache {

    /** 缓存条目：检索结果 + 写入时间戳 */
    private record TimestampedValue(List<SearchResult> value, long timestamp) {
    }

    /** 缓存上限（写入时 size 截断） */
    static final int MAX_ENTRIES = 256;

    private final ConcurrentHashMap<String, TimestampedValue> cache = new ConcurrentHashMap<>();

    private final boolean enabled;

    private final long ttlMs;

    public QueryTtlCache(
            @Value("${travel.rag.query-cache.enabled:true}") boolean enabled,
            @Value("${travel.rag.query-cache.ttl-ms:60000}") long ttlMs) {
        this.enabled = enabled;
        this.ttlMs = ttlMs;
    }

    /**
     * 生成规范化缓存键：策略 + 城市 + 意图快照（rawQuery / 排序 keywords / type / freeOnly）+ topK。
     *
     * <p>方案口径"query+策略+城市"为最小集，此处纳入 dispatch 全部语义入参，
     * 防止不同 topK/类型约束间的错误命中。</p>
     */
    public static String buildKey(String ragType, QueryIntent intent, int topK) {
        String type = ragType == null ? "auto" : ragType.trim().toLowerCase();
        String city = intent == null || intent.city() == null ? "" : intent.city().trim().toLowerCase();
        String raw = intent == null || intent.rawQuery() == null ? "" : intent.rawQuery().trim().toLowerCase();
        List<String> keywords = intent == null || intent.keywords() == null
                ? List.of() : intent.keywords().stream()
                .map(k -> k == null ? "" : k.trim().toLowerCase())
                .sorted()
                .toList();
        String intentType = intent == null || intent.type() == null ? "" : intent.type().trim().toLowerCase();
        boolean freeOnly = intent != null && intent.freeOnly();
        return type + '|' + city + '|' + raw + '|' + intentType + '|' + freeOnly + '|'
                + String.join(",", keywords) + '|' + topK;
    }

    /**
     * 命中且未过期返回缓存值；未命中 / 已过期 / 开关关闭返回 null（读时惰性过期）。
     */
    public List<SearchResult> get(String key) {
        if (!enabled) {
            return null;
        }
        TimestampedValue hit = cache.get(key);
        if (hit == null) {
            return null;
        }
        if (System.currentTimeMillis() - hit.timestamp() > ttlMs) {
            cache.remove(key);
            return null;
        }
        return hit.value();
    }

    /**
     * 写入缓存（开关关闭或值为 null 时直通忽略）；写入时顺带清扫过期项并执行上限截断。
     */
    public void put(String key, List<SearchResult> value) {
        if (!enabled || value == null) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.values().removeIf(v -> now - v.timestamp() > ttlMs);
        while (cache.size() >= MAX_ENTRIES) {
            evictOldest();
        }
        cache.put(key, new TimestampedValue(value, now));
    }

    /**
     * 简单 size 截断：淘汰时间戳最旧的一条（n ≤ 256，写入路径 O(n) 可接受）
     */
    private void evictOldest() {
        Map.Entry<String, TimestampedValue> oldest = null;
        for (Map.Entry<String, TimestampedValue> entry : cache.entrySet()) {
            if (oldest == null || entry.getValue().timestamp() < oldest.getValue().timestamp()) {
                oldest = entry;
            }
        }
        if (oldest != null) {
            cache.remove(oldest.getKey());
        }
    }

    /** 当前条目数（包级可见，测试断言用） */
    int size() {
        return cache.size();
    }
}
