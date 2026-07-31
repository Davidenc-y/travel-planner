package com.travel.knowledge.controller;

import com.travel.common.result.R;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.service.AttractionImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
@RequiredArgsConstructor
public class EtlController {

    private final AttractionEtlService etlService;
    private final AttractionImportService importService;

    /**
     * 全量 ETL：处理所有景点（含已索引的会重新写入）
     *
     * <p>使用场景：Milvus/ES 数据丢失后重建。</p>
     */
    @PostMapping("/all")
    public R<Integer> etlAll() {
        log.info("触发全量 ETL");
        return R.ok(etlService.etlAll());
    }

    /**
     * 增量 ETL：仅处理未索引景点
     *
     * <p>使用场景：新增景点后同步到 Milvus + ES。</p>
     */
    @PostMapping("/unindexed")
    public R<Integer> etlUnindexed() {
        log.info("触发增量 ETL");
        return R.ok(etlService.etlUnindexed());
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
     * 从 JSON 文件导入景点数据
     *
     * @param filePath JSON 文件绝对路径
     * @return 成功导入数量
     */
    @PostMapping("/import")
    public R<Integer> importFromJson(@RequestParam String filePath) {
        log.info("触发数据导入: {}", filePath);
        try {
            return R.ok(importService.importFromJsonFile(filePath));
        } catch (Exception e) {
            log.error("数据导入失败", e);
            return R.fail(50003, "数据导入失败: " + e.getMessage());
        }
    }
}
