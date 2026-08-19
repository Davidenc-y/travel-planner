package com.travel.knowledge.service;

import com.travel.common.entity.Attraction;
import com.travel.common.util.JsonUtils;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.repository.AttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
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
        return importWithStats(filePath, mode).inserted();
    }

    /**
     * 从 JSON 文件导入景点并返回统计（F104 2.9：insert/upsert）
     *
     * @return {inserted, updated, skipped}
     */
    @Transactional
    public ImportStats importWithStats(String filePath, String mode) throws Exception {
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
        for (Attraction a : attractions) {
            try {
                // 检查是否已存在（按名称+城市去重）
                Attraction existing = attractionMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attraction>()
                                .eq(Attraction::getName, a.getName())
                                .eq(Attraction::getCity, a.getCity()));
                if (existing != null) {
                    if (!"upsert".equalsIgnoreCase(mode)) {
                        skip++;
                        continue;
                    }
                    // F104 2.9 upsert：复制可更新字段（保留 id/审计字段），重新 ETL 生效
                    // F108：空字段不覆盖既有值（AMap 部分 POI 缺 rating/ticketPrice/description，
                    // 直接拷贝 null 会清空库内已有优质数据）。
                    java.util.List<String> ignore = new java.util.ArrayList<>(
                            java.util.List.of("id", "createdAt", "updatedAt", "indexed", "deleted"));
                    if (a.getRating() == null) ignore.add("rating");
                    if (a.getRatingCount() == null) ignore.add("ratingCount");
                    if (a.getTicketPrice() == null) ignore.add("ticketPrice");
                    if (a.getFreeEntry() == null) ignore.add("freeEntry");
                    if (a.getDescription() == null) ignore.add("description");
                    if (a.getLat() == null) ignore.add("lat");
                    if (a.getLng() == null) ignore.add("lng");
                    if (a.getAddress() == null) ignore.add("address");
                    if (a.getOpenHours() == null) ignore.add("openHours");
                    if (a.getRecommendedDuration() == null) ignore.add("recommendedDuration");
                    if (a.getTags() == null) ignore.add("tags");
                    if (a.getImageUrl() == null) ignore.add("imageUrl");
                    org.springframework.beans.BeanUtils.copyProperties(a, existing,
                            ignore.toArray(new String[0]));
                    existing.setIndexed(0);
                    attractionMapper.updateById(existing);
                    etlService.etlOne(existing);
                    updated++;
                    continue;
                }

                // 设置默认值
                if (a.getIndexed() == null) a.setIndexed(0);
                if (a.getFreeEntry() == null) a.setFreeEntry(0);
                if (a.getSource() == null) a.setSource("manual");

                attractionMapper.insert(a);

                // 同步触发 ETL
                etlService.etlOne(a);
                success++;
            } catch (Exception e) {
                log.error("导入失败: name={}, city={}, error={}",
                        a.getName(), a.getCity(), e.getMessage());
            }
        }

        log.info("导入完成(mode={}): 总计={}, 新增={}, 更新={}, 跳过={}",
                mode, attractions.size(), success, updated, skip);
        return new ImportStats(success, updated, skip);
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
