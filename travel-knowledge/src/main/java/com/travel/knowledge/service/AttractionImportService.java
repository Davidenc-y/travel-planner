package com.travel.knowledge.service;

import com.travel.common.entity.Attraction;
import com.travel.core.data.MergeRules;
import com.travel.core.data.SourceConfidence;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.repository.AttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 景点数据导入服务
 *
 * <p>从 JSON 文件导入景点数据到 MySQL，并触发 ETL 写入 Milvus + ES。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttractionImportService {

    /** F104 2.9：导入统计（新增/更新/跳过），供流水线与测试接口观测 */
    public record ImportStats(int inserted, int updated, int skipped) {
    }

    /** F119：导入结果 = 统计 + 受影响行（入库事务提交后由调用方并行 ETL） */
    public record ImportResult(ImportStats stats, List<Attraction> affected) {
    }

    private final AttractionMapper attractionMapper;
    private final AttractionEtlService etlService;

    /**
     * 从 JSON 文件导入景点
     *
     * @param filePath JSON 文件路径
     * @return 成功导入数量
     */
    @Transactional
    public int importFromJsonFile(String filePath) throws Exception {
        return importFromJsonFile(filePath, "insert");
    }

    /**
     * 从 JSON 文件导入景点（F104 2.9：支持 insert/upsert 去重与更新）
     *
     * @param filePath JSON 文件路径
     * @param mode     insert（默认，已存在跳过）/ upsert（已存在更新，爬虫刷新用）
     * @return 成功新增数量（保持与 TC-13 口径兼容；updated/skip 记日志）
     */
    @Transactional
    public int importFromJsonFile(String filePath, String mode) throws Exception {
        return importWithStats(filePath, mode).stats().inserted();
    }

    /**
     * 从 JSON 文件导入景点并返回统计（F104 2.9：insert/upsert）
     *
     * @return {inserted, updated, skipped}
     */
    @Transactional
    public ImportResult importWithStats(String filePath, String mode) throws Exception {
        // F30：统一路径分隔符（Windows 反斜杠 → 正斜杠），
        // 避免 Postman 传入原始反斜杠或 URL 编码差异导致 File 定位失败；跨平台均安全。
        String normalizedPath = filePath == null ? null : filePath.replace('\\', '/');
        log.info("开始导入景点数据: {}", normalizedPath);
        File file = new File(normalizedPath);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在: " + normalizedPath);
        }

        List<Attraction> attractions = JsonUtils.getMapper().readValue(file,
                JsonUtils.getMapper().getTypeFactory().constructCollectionType(List.class, Attraction.class));

        int success = 0;
        int updated = 0;
        int skip = 0;
        List<Attraction> affected = new ArrayList<>();
        for (Attraction a : attractions) {
            try {
                // 检查是否已存在（按名称+城市去重）
                Attraction existing = findExisting(a);
                if (existing != null) {
                    if (!"upsert".equalsIgnoreCase(mode)) {
                        skip++;
                        continue;
                    }
                    // F110-B：字段级合并策略（非空 且 来源置信度不低于现有值才覆盖），
                    // 取代 F108 的空值保护特例；身份字段 name/city/poiId 不覆盖。
                    mergeFields(existing, a,
                            SourceConfidence.ofSource(existing.getSource()),
                            SourceConfidence.ofSource(a.getSource()));
                    existing.setIndexed(0);
                    attractionMapper.updateById(existing);
                    affected.add(existing);
                    updated++;
                    continue;
                }

                // 设置默认值
                if (a.getIndexed() == null) a.setIndexed(0);
                if (a.getFreeEntry() == null) a.setFreeEntry(0);
                if (a.getSource() == null) a.setSource("manual");

                attractionMapper.insert(a);
                affected.add(a);
                success++;
            } catch (Exception e) {
                log.error("导入失败: name={}, city={}, error={}",
                        a.getName(), a.getCity(), e.getMessage());
            }
        }

        log.info("导入完成(mode={}): 总计={}, 新增={}, 更新={}, 跳过={}",
                mode, attractions.size(), success, updated, skip);
        return new ImportResult(new ImportStats(success, updated, skip), affected);
    }

    /** 存在性查询：优先 poi_id（F110-B 幂等键），其次 name+city */
    private Attraction findExisting(Attraction a) {
        if (a.getPoiId() != null && !a.getPoiId().isBlank()) {
            Attraction byPoi = attractionMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attraction>()
                            .eq(Attraction::getPoiId, a.getPoiId()));
            if (byPoi != null) {
                return byPoi;
            }
        }
        return attractionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attraction>()
                        .eq(Attraction::getName, a.getName())
                        .eq(Attraction::getCity, a.getCity()));
    }

    /** F110-B 字段级合并：incoming 非空且置信度 >= 现有值时覆盖；身份字段除外 */
    private void mergeFields(Attraction t, Attraction incoming,
                             SourceConfidence tConf, SourceConfidence inConf) {
        t.setDistrict(MergeRules.choose(t.getDistrict(), tConf, incoming.getDistrict(), inConf));
        t.setType(MergeRules.choose(t.getType(), tConf, incoming.getType(), inConf));
        t.setDescription(MergeRules.choose(t.getDescription(), tConf, incoming.getDescription(), inConf));
        t.setLat(MergeRules.choose(t.getLat(), tConf, incoming.getLat(), inConf));
        t.setLng(MergeRules.choose(t.getLng(), tConf, incoming.getLng(), inConf));
        t.setAddress(MergeRules.choose(t.getAddress(), tConf, incoming.getAddress(), inConf));
        t.setOpenHours(MergeRules.choose(t.getOpenHours(), tConf, incoming.getOpenHours(), inConf));
        t.setTicketPrice(MergeRules.choose(t.getTicketPrice(), tConf, incoming.getTicketPrice(), inConf));
        t.setFreeEntry(MergeRules.choose(t.getFreeEntry(), tConf, incoming.getFreeEntry(), inConf));
        t.setRating(MergeRules.choose(t.getRating(), tConf, incoming.getRating(), inConf));
        t.setRatingCount(MergeRules.choose(t.getRatingCount(), tConf, incoming.getRatingCount(), inConf));
        t.setTags(MergeRules.choose(t.getTags(), tConf, incoming.getTags(), inConf));
        t.setRecommendedDuration(MergeRules.choose(
                t.getRecommendedDuration(), tConf, incoming.getRecommendedDuration(), inConf));
        t.setImageUrl(MergeRules.choose(t.getImageUrl(), tConf, incoming.getImageUrl(), inConf));
        t.setSource(MergeRules.choose(t.getSource(), tConf, incoming.getSource(), inConf));
        if (t.getPoiId() == null && incoming.getPoiId() != null) {
            t.setPoiId(incoming.getPoiId());
        }
    }

    /**
     * 导入单个景点
     */
    @Transactional
    public boolean importOne(Attraction attraction) {
        try {
            attractionMapper.insert(attraction);
            etlService.etlOne(attraction);
            log.info("景点导入成功: name={}", attraction.getName());
            return true;
        } catch (Exception e) {
            log.error("景点导入失败: name={}, error={}", attraction.getName(), e.getMessage());
            return false;
        }
    }
}
