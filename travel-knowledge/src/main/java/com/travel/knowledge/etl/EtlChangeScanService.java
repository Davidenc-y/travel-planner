package com.travel.knowledge.etl;

import com.travel.common.entity.Attraction;
import com.travel.knowledge.repository.AttractionMapper;
import com.travel.knowledge.store.EsDocumentStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RK-4：MySQL↔ES 内容 hash 对账扫描。默认关闭（travel.etl.change-scan.enabled=false），
 * 开启后按 interval-ms 调度对账，缺文档/hash 漂移的景点经 etlOne 重灌（同时刷新 ES+Milvus）。
 * 日志契约：[EtlChangeScan] scanned=… missing=… stale=… reindexed=…
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EtlChangeScanService {

    /** 与 AttractionEtlService.ES_INDEX 同值（该常量 private，本类零触碰既有类） */
    private static final String ES_INDEX = "attraction_index";

    private final AttractionEtlService etlService;
    private final AttractionMapper attractionMapper;   // MyBatis-Plus BaseMapper（selectList 现算侧）
    private final EsDocumentStore esDocumentStore;     // 既有封装，search(index, queryBuilder, size)
    private final EtlChangeScanProperties properties;

    @Scheduled(fixedDelayString = "${travel.etl.change-scan.interval-ms:86400000}")
    public void scheduledScan() {
        if (!properties.isEnabled()) return;
        scan(properties.getBatchSize());
    }

    /** 返回成功重灌条数（供测试直调）。 */
    public int scan(int limit) {
        // 1. MySQL 全量先行（决定对照基数）；ES 取数覆盖整个对照基数（size=max(预算, 语料数)，
        //    取大避免 ES 分页截断导致误判 missing——审计修复 2026-09-20：原实现两侧各截前 limit 条，
        //    超出 limit 的语料永远对不上且两侧排序不保证对齐）
        List<Attraction> attractions = attractionMapper.selectList(null);
        Map<String, String> esHashes = new HashMap<>();
        try {
            int fetchSize = Math.max(limit, attractions.size());
            for (SearchHit hit : esDocumentStore.search(ES_INDEX, QueryBuilders.matchAllQuery(), fetchSize)) {
                Object hash = hit.getSourceAsMap().get("contentHash");
                if (hash != null) {
                    esHashes.put(hit.getId(), hash.toString());
                }
            }
        } catch (Exception e) {
            log.warn("[EtlChangeScan] ES 读取失败，本轮对账中止: {}", e.getMessage());
            return 0;
        }
        // 2. 全量对照（比较零成本，重灌才是成本）：ES 缺失 → missing；hash 不等 → stale；
        //    一致 → 保持。limit=单轮重灌预算，预算用尽后剩余缺失/漂移留待下轮确定性续灌（无游标，幂等收敛）
        int scanned = 0;
        int missing = 0;
        int stale = 0;
        int reindexed = 0;
        for (Attraction attraction : attractions) {
            scanned++;
            String docId = String.valueOf(attraction.getId());
            String expected = AttractionEtlService.contentHashOf(etlService.buildContent(attraction));
            String esHash = esHashes.get(docId);
            if (esHash == null) {
                missing++;
            } else if (!esHash.equals(expected)) {
                stale++;
            } else {
                continue;
            }
            if (reindexed >= limit) {
                continue;
            }
            // 3. 重灌走既有 etlOne（节流已内建）；异常逐条吞并计数（失败不计入 reindexed）
            try {
                if (etlService.etlOne(attraction)) {
                    reindexed++;
                }
            } catch (Exception e) {
                log.warn("[EtlChangeScan] 重灌失败吞并: id={} err={}", docId, e.getMessage());
            }
        }
        log.info("[EtlChangeScan] scanned={} missing={} stale={} reindexed={}", scanned, missing, stale, reindexed);
        return reindexed;
    }
}
