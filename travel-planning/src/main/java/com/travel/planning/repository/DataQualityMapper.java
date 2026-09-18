package com.travel.planning.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * E-5a：数据质量端点只读计数（t_etl_outbox 为 knowledge ETL outbox 共享表，
 * DG-3 DDL 已由人工执行；本 Mapper 仅 SELECT 纯读、零 schema 变更）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface DataQualityMapper {

    /** outbox 未消费计数（consumed=0，DG-3b 写入 / DG-3c 消费置 1）。 */
    @Select("SELECT COUNT(*) FROM t_etl_outbox WHERE consumed = 0")
    long countEtlOutboxUnconsumed();

    /** outbox 全量计数。 */
    @Select("SELECT COUNT(*) FROM t_etl_outbox")
    long countEtlOutboxTotal();
}
