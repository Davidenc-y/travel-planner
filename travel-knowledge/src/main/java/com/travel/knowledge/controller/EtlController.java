package com.travel.knowledge.controller;

import com.travel.common.cache.RedisResultCache;
import com.travel.common.result.R;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.etl.CacheInvalidationContract;
import com.travel.knowledge.repository.AttractionMapper;
import com.travel.knowledge.service.AttractionImportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ETL 管理接口
 *
 * <p>提供 ETL 触发、统计、数据导入等管理端点。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/etl")
public class EtlController {

    private final AttractionEtlService etlService;
    private final AttractionImportService importService;
    private final AttractionMapper attractionMapper;
    /** AP-A5a：city-counts 缓存失效广播发布端（契约=CacheInvalidationContract，P0㉖ 逐字冻结） */
    private final StringRedisTemplate redisTemplate;
    /** BB-2：attraction 结果缓存（keyPrefix travel:attr:，与 AttractionService 同前缀共享失效链） */
    private final RedisResultCache attractionCache;

    public EtlController(AttractionEtlService etlService,
                         AttractionImportService importService,
                         AttractionMapper attractionMapper,
                         StringRedisTemplate redisTemplate) {
        this.etlService = etlService;
        this.importService = importService;
        this.attractionMapper = attractionMapper;
        this.redisTemplate = redisTemplate;
        this.attractionCache = new RedisResultCache(redisTemplate, "travel:attr:");
    }

    /**
     * 全量 ETL：处理所有景点（含已索引的会重新写入）
     *
     * <p>使用场景：Milvus/ES 数据丢失后重建。</p>
     */
    @PostMapping("/all")
    public R<Integer> etlAll() {
        log.info("触发全量 ETL");
        int count = etlService.etlAll();
        // AP-A5a（GP-5）：全量导入成功→广播 city-counts 失效（planning CityCorpusCache 即时失效）
        publishCityCountsInvalidation();
        // BB-2：ETL 写点→attraction 缓存失效（追加不替换，P0-⑷）
        attractionCache.evict("cities");
        // 城市坐标按需失效——四问复审修正：keys() 改 SCAN（与 BB-5 AdminCacheController 同口径，
        // 防 KEYS 阻塞 Redis 主线程；量级 <100 键微秒级，但统一模式防未来量级增长时踩坑）
        try (org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("travel:attr:coords:*").count(100).build())) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (cursor.hasNext()) keys.add(cursor.next());
            if (!keys.isEmpty()) redisTemplate.delete(keys);
        } catch (Exception e) {
            // P0-⑴ fail-open：Redis 异常/scan 空 仅 WARN 跳过，ETL 主链路零阻断（与广播同口径）
            log.warn("coords 缓存 SCAN 失效跳过（fail-open）: {}", String.valueOf(e));
        }
        return R.ok(count);
    }

    /**
     * 增量 ETL：仅处理未索引景点
     *
     * <p>使用场景：新增景点后同步到 Milvus + ES。</p>
     */
    @PostMapping("/unindexed")
    public R<Integer> etlUnindexed() {
        log.info("触发增量 ETL");
        int count = etlService.etlUnindexed();
        // AP-A5a（GP-5）：增量导入成功→广播 city-counts 失效
        publishCityCountsInvalidation();
        // BB-2：ETL 写点→attraction 缓存失效（追加不替换，P0-⑷）
        attractionCache.evict("cities");
        // 城市坐标按需失效——四问复审修正：keys() 改 SCAN（与 BB-5 AdminCacheController 同口径，
        // 防 KEYS 阻塞 Redis 主线程；量级 <100 键微秒级，但统一模式防未来量级增长时踩坑）
        try (org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match("travel:attr:coords:*").count(100).build())) {
            java.util.List<String> keys = new java.util.ArrayList<>();
            while (cursor.hasNext()) keys.add(cursor.next());
            if (!keys.isEmpty()) redisTemplate.delete(keys);
        } catch (Exception e) {
            // P0-⑴ fail-open：Redis 异常/scan 空 仅 WARN 跳过，ETL 主链路零阻断（与广播同口径）
            log.warn("coords 缓存 SCAN 失效跳过（fail-open）: {}", String.valueOf(e));
        }
        return R.ok(count);
    }

    /**
     * ETL 统计信息
     *
     * @return {total, indexed, unindexed}
     */
    @GetMapping("/stats")
    public R<Map<String, Object>> getStats() {
        return R.ok(etlService.getStats());
    }

    /**
     * AL-2a（GL-2）：城市语料计数——planning 侧 CityCorpusCache 数据源（只读观测面，
     * internal-token 保护前缀 /api/v1/etl/** 内）。行结构 {city, c} 转置为 Map 返回。
     */
    @GetMapping("/city-counts")
    public R<Map<String, Integer>> cityCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : attractionMapper.cityCounts()) {
            if (row.get("city") != null && row.get("c") instanceof Number n) {
                counts.put(row.get("city").toString(), n.intValue());
            }
        }
        return R.ok(counts);
    }

    /**
     * 从 JSON 文件导入景点数据
     *
     * @param filePath JSON 文件绝对路径
     * @return 成功导入数量
     */
    /** M21-3（SEC-02-05）：导入目录白名单（fail-closed：未配置 base-dir 时拒绝一切导入）。 */
    @org.springframework.beans.factory.annotation.Value("${travel.etl.import-base-dir:}")
    private String importBaseDir;

    @PostMapping("/import")
    public R<Integer> importFromJson(@RequestParam String filePath,
                                     @RequestParam(defaultValue = "insert") String mode,
                                     HttpServletResponse response) {
        requireImportPathAllowed(filePath);
        log.info("触发数据导入: baseDir 内文件");
        try {
            AttractionImportService.ImportResult result = importService.importWithStats(filePath, mode);
            // F119：入库事务提交后，并行 ETL（await 完成，契约不变）
            int etlOk = etlService.etlBatch(result.affected());
            log.info("导入后并行 ETL: 处理 {} 条, 成功 {} 条", result.affected().size(), etlOk);
            // AP-A5a（GP-5）：导入成功（含并行 ETL）→广播 city-counts 失效；失败路径 catch 内不发布
            publishCityCountsInvalidation();
            // BB-2：ETL 写点→attraction 缓存失效（追加不替换，P0-⑷）
            attractionCache.evict("cities");
            // 城市坐标按需失效——四问复审修正：keys() 改 SCAN（与 BB-5 AdminCacheController 同口径，
            // 防 KEYS 阻塞 Redis 主线程；量级 <100 键微秒级，但统一模式防未来量级增长时踩坑）
            try (org.springframework.data.redis.core.Cursor<String> cursor = redisTemplate.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match("travel:attr:coords:*").count(100).build())) {
                java.util.List<String> keys = new java.util.ArrayList<>();
                while (cursor.hasNext()) keys.add(cursor.next());
                if (!keys.isEmpty()) redisTemplate.delete(keys);
            } catch (Exception e) {
                // P0-⑴ fail-open：Redis 异常/scan 空 仅 WARN 跳过，ETL 主链路零阻断（与广播同口径）
                log.warn("coords 缓存 SCAN 失效跳过（fail-open）: {}", String.valueOf(e));
            }
            // F104 2.9：透传新增/更新/跳过统计（TC-13 的 R<Integer> 契约不变）
            response.setHeader("X-Import-Stats",
                    "{\"inserted\":" + result.stats().inserted()
                            + ",\"updated\":" + result.stats().updated()
                            + ",\"skipped\":" + result.stats().skipped() + "}");
            return R.ok(result.stats().inserted());
        } catch (Exception e) {
            // M21-3（SEC-05-06）：错误响应不再回显原始路径（防路径探测 oracle），详情仅入服务端日志
            log.error("数据导入失败: file={}", filePath, e);
            return R.fail(50003, "数据导入失败，详情见服务端日志");
        }
    }

    /**
     * AP-A5a（GP-5）：city-counts 缓存失效广播——planning 侧 CityCorpusCache 订阅即时失效
     * （AP-B2），替代既有 600s TTL 滞后。契约单源=方案 §〇（P0㉖ 逐字冻结，禁自创字段）。
     * fail-open：广播尽力而为——Redis 不可达仅 WARN，导入主链路零阻断（退化为既有 TTL 行为）。
     */
    private void publishCityCountsInvalidation() {
        try {
            redisTemplate.convertAndSend(CacheInvalidationContract.CHANNEL_CITY_COUNTS,
                    CacheInvalidationContract.PAYLOAD_CITY_COUNTS);
            log.info("已发布 city-counts 失效广播: channel={}", CacheInvalidationContract.CHANNEL_CITY_COUNTS);
        } catch (Exception e) {
            log.warn("city-counts 失效广播发布失败（fail-open，缓存按 TTL 过期）: {}", String.valueOf(e));
        }
    }

    /**
     * M21-3（SEC-02-05 止血）：filePath 必须位于配置的导入根目录内
     * （toRealPath 前缀断言，阻断 ../穿越与任意路径读/知识库投毒入口）。
     */
    private void requireImportPathAllowed(String filePath) {
        if (importBaseDir == null || importBaseDir.isBlank()) {
            throw new com.travel.common.exception.BusinessException(
                    40302, "数据导入目录未配置，导入功能已关闭");
        }
        try {
            java.nio.file.Path base = java.nio.file.Paths.get(importBaseDir).toRealPath();
            java.nio.file.Path requested = java.nio.file.Paths.get(filePath).toAbsolutePath().normalize();
            // M22-3（Reviewer #5）：requested 亦归一化真实路径（阻断符号链接前缀伪造）；
            // 不存在的文件随后续读取自然报错，此处仅在可解析时做前缀断言
            if (java.nio.file.Files.exists(requested)) {
                requested = requested.toRealPath();
            }
            if (!requested.startsWith(base)) {
                throw new com.travel.common.exception.BusinessException(
                        40302, "数据文件必须位于配置的导入目录内");
            }
        } catch (java.io.IOException e) {
            throw new com.travel.common.exception.BusinessException(40302, "导入目录不可用");
        }
    }
}
