package com.travel.crawl.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程安全的 LRU + TTL 缓存（F115 T4）：访问序淘汰 + 过期淘汰。
 * 用于 Wikidata 实体结果缓存（默认 7 天），减少重复请求。
 */
public class LruTtlCache<K, V> {

    private final long ttlMillis;
    private final LinkedHashMap<K, Entry<V>> map;

    public LruTtlCache(int capacity, long ttlMillis) {
        this.ttlMillis = ttlMillis;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                return size() > capacity;
            }
        };
    }

    public synchronized V get(K key) {
        Entry<V> e = map.get(key);
        if (e == null) {
            return null;
        }
        if (System.currentTimeMillis() > e.expireAt) {
            map.remove(key);
            return null;
        }
        return e.value;
    }

    public synchronized void put(K key, V value) {
        map.put(key, new Entry<>(value, System.currentTimeMillis() + ttlMillis));
    }

    public synchronized int size() {
        return map.size();
    }

    private record Entry<V>(V value, long expireAt) {
    }
}
