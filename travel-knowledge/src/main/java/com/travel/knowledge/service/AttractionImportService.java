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
        log.info("开始导入景点数据: {}", filePath);
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException("文件不存在: " + filePath);
        }

        List<Attraction> attractions = JsonUtils.getMapper().readValue(file,
                JsonUtils.getMapper().getTypeFactory().constructCollectionType(List.class, Attraction.class));

        int success = 0;
        int skip = 0;
        for (Attraction a : attractions) {
            try {
                // 检查是否已存在（按名称+城市去重）
                Attraction existing = attractionMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Attraction>()
                                .eq(Attraction::getName, a.getName())
                                .eq(Attraction::getCity, a.getCity()));
                if (existing != null) {
                    skip++;
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

        log.info("导入完成: 总计={}, 成功={}, 跳过(已存在)={}", attractions.size(), success, skip);
        return success;
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
