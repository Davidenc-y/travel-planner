package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.knowledge.etl.EtlOutbox;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * DG-3a：ETL 变更事件 outbox Mapper（表 t_etl_outbox；DDL 由人工/审计执行——E-13）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface EtlOutboxMapper extends BaseMapper<EtlOutbox> {

    /** DG-3c-fix：事件消费成功后将该景点未消费事件标记 consumed=1（PD-1 断言①；幂等只标未消费行） */
    @Update("UPDATE t_etl_outbox SET consumed = 1 WHERE attraction_id = #{attractionId} AND consumed = 0")
    int markConsumed(@Param("attractionId") Long attractionId);
}
