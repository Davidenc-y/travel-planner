package com.travel.knowledge.etl;

import com.travel.common.entity.Attraction;

import java.util.List;
import java.util.Map;

/**
 * AttractionEtlService 公共契约（AD-2c 接口化）。
 *
 * <p>实现见 {@link AttractionEtlServiceImpl}（@Service 在 Impl）；公有方法签名逐字提取
 * （零语义变更）。buildContent/contentHashOf 原为包私有（EtlChangeScanService 同包 main
 * 调用方在位），接口化后升 public 入契约（纯函数可见性放大零行为影响，留痕）。</p>
 */
public interface AttractionEtlService {

    /** 单点重建索引（embedding+向量库）。 */
    boolean reindexOne(Long attractionId);

    /** 批量 ETL（返回处理数）。 */
    int etlBatch(List<Attraction> rows);

    /** 全量 ETL。 */
    int etlAll();

    /** 未索引增量 ETL。 */
    int etlUnindexed();

    /** 单点 ETL（返回成功与否）。 */
    boolean etlOne(Attraction attraction);

    /** 定时 ETL 入口（调度触发）。 */
    void scheduledEtl();

    /** ETL 统计（total/indexed/unindexed）。 */
    Map<String, Object> getStats();

    /** 检索内容组装（embedding 输入文本，EtlChangeScanService 契约依赖）。 */
    String buildContent(Attraction a);

    /** 内容哈希（变更扫描期望值计算，EtlChangeScanService 契约依赖）。 */
    static String contentHashOf(String content) {
        return AttractionEtlServiceImpl.contentHashOf(content);
    }
}
