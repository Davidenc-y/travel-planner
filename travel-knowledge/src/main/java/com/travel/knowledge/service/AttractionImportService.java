package com.travel.knowledge.service;

import com.travel.common.entity.Attraction;

import java.util.List;

/**
 * AttractionImportService 公共契约（AD-2e 接口化）。
 *
 * <p>实现见 {@link AttractionImportServiceImpl}（@Service 在 Impl）；公有方法签名逐字提取
 * （零语义变更）。两结果 record 迁入本接口（EtlController main FQN 调用方在位，零变动）。</p>
 */
public interface AttractionImportService {

    /** JSON 文件导入（默认模式）。 */
    int importFromJsonFile(String filePath) throws Exception;

    /** JSON 文件导入（insert/upsert 模式）。 */
    int importFromJsonFile(String filePath, String mode) throws Exception;

    /** 带统计导入（stats+受影响行）。 */
    ImportResult importWithStats(String filePath, String mode) throws Exception;

    /** 单条导入（upsert 语义）。 */
    boolean importOne(Attraction attraction);

    /** F104 2.9：导入统计（新增/更新/跳过），供流水线与测试接口观测 */
    record ImportStats(int inserted, int updated, int skipped) {
    }

    /** F119：导入结果 = 统计 + 受影响行（入库事务提交后由调用方并行 ETL） */
    record ImportResult(ImportStats stats, List<Attraction> affected) {
    }
}
