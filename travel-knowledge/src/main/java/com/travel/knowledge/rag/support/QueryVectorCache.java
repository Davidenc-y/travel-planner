package com.travel.knowledge.rag.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AO-1a（GO-1）：查询向量 LRU 缓存——同文本零 API 复用（热路径真零化）。
 *
 * <p>QU 缓存命中时 query 文本稳定 → 向量确定性可缓存；容量/TTL 双键（模型版本换代由 TTL
 * 自然过期防混用）。命中/未命中双计数（rag.embedding.cache_hit/miss）；键 0=关（E-33）。</p>
 */
@Component
public class QueryVectorCache {

    /** AO-1a：容量键（默认 256=缓存默认开=纯读优化，方案 §2.1 差异性声明；0=关 E-33；包内可见=单测直设面） */
    @Value("${travel.rag.embedding.cache-size:256}")
    int cacheSize = 256;

    /** AO-1a：TTL 键（默认 86400000=24h，模型换代防混用；包内可见=单测直设面） */
    @Value("${travel.rag.embedding.cache-ttl-ms:86400000}")
    long cacheTtlMs = 86400000L;

    private final RagRoutingMetrics metrics;

    /** 向量 LRU 缓存（access-order，QU 缓存同款先例=LinkedHashMap+removeEldestEntry） */
    private final Map<String, Entry> cache;

    /** 缓存条目（向量引用+落盘时刻，TTL 判定用） */
    private static final class Entry {
        final float[] vector;
        final long storedAt;

        Entry(float[] vector, long storedAt) {
            this.vector = vector;
            this.storedAt = storedAt;
        }
    }

    public QueryVectorCache(RagRoutingMetrics metrics) {
        this.metrics = metrics;
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return cacheSize > 0 && size() > cacheSize;
            }
        });
    }

    /**
     * TTL 内命中返回缓存向量；未命中/过期/键 0=关 一律返回 null（调用方走 embed 路并计数）。
     */
    public float[] get(String text) {
        if (cacheSize <= 0) {
            return null;
        }
        Entry entry = cache.get(text);
        if (entry == null) {
            metrics.recordEmbeddingCacheMiss();
            return null;
        }
        if (System.currentTimeMillis() - entry.storedAt > cacheTtlMs) {
            cache.remove(text);
            metrics.recordEmbeddingCacheMiss();
            return null;
        }
        metrics.recordEmbeddingCacheHit();
        return entry.vector;
    }

    /**
     * 成功 embed 结果入缓存（仅成功结果可缓存=P0㉑；键 0=关或 null 向量拒入）。
     */
    public void put(String text, float[] vector) {
        if (cacheSize <= 0 || vector == null) {
            return;
        }
        cache.put(text, new Entry(vector, System.currentTimeMillis()));
    }
}
