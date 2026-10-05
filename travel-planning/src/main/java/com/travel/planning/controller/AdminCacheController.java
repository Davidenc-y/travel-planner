package com.travel.planning.controller;

import com.travel.common.cache.RedisKeyRegistry;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.util.AuthUtils;
import com.travel.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * BB-5：Redis 缓存总览/手动清除端点——消费 RedisKeyRegistry 逐前缀 SCAN 统计。
 * GET  /admin/redis/caches          → 全部前缀的键数/样例键/TTL
 * DELETE /admin/redis/caches/{prefix-index} → 按注册表索引手动清除（应急/调试）
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/redis")
@RequiredArgsConstructor
public class AdminCacheController {

    private final StringRedisTemplate redisTemplate;
    private final AdminAccessService adminAccessService;

    @GetMapping("/caches")
    public Map<String, Object> listCaches() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问缓存管理");
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        RedisKeyRegistry.KeyEntry[] registry = RedisKeyRegistry.ENTRIES;
        for (int i = 0; i < registry.length; i++) {
            RedisKeyRegistry.KeyEntry e = registry[i];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("pattern", e.pattern());
            row.put("module", e.module());
            row.put("purpose", e.purpose());
            row.put("ttlType", e.ttl().name());
            row.put("eviction", e.eviction().name());
            // SCAN 统计键数（逐前缀，键数量级 <1000 可接受）
            try (Cursor<String> cursor = redisTemplate.scan(
                    ScanOptions.scanOptions().match(e.pattern()).count(100).build())) {
                long count = 0;
                List<String> samples = new ArrayList<>();
                while (cursor.hasNext() && count < 5) {
                    String key = cursor.next();
                    count++;
                    if (samples.size() < 3) samples.add(key);
                }
                row.put("sampleKeys", samples);
            } catch (Exception ex) {
                row.put("error", "scan failed: " + ex.getMessage());
            }
            entries.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalPatterns", registry.length);
        result.put("entries", entries);
        return result;
    }

    @DeleteMapping("/caches/{index}/evict")
    public Map<String, Object> evictByIndex(@PathVariable int index) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问缓存管理");
        }
        if (index < 0 || index >= RedisKeyRegistry.ENTRIES.length) {
            throw new BusinessException(40001, "无效索引: " + index);
        }
        RedisKeyRegistry.KeyEntry e = RedisKeyRegistry.ENTRIES[index];
        long deleted = 0;
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(e.pattern()).count(100).build())) {
            List<String> keys = new ArrayList<>();
            while (cursor.hasNext()) keys.add(cursor.next());
            if (!keys.isEmpty()) {
                deleted = redisTemplate.delete(keys);
            }
        }
        log.info("[CacheAdmin] 手动清除: pattern={}, deleted={}", e.pattern(), deleted);
        return Map.of("pattern", e.pattern(), "deleted", deleted);
    }
}
