package com.travel.knowledge.controller;

import com.travel.common.result.R;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.service.AttractionImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;

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
