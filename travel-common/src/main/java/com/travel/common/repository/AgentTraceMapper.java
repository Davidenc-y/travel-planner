package com.travel.common.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * RK-15：t_agent_trace 唯一 Mapper（原 memory/knowledge 双副本收敛）。
 *
 * <p>方法面=原 memory 副本超集逐字搬移（BaseMapper + E-5b 两自定义查询；
 * knowledge 副本原为纯 BaseMapper 子集）；E-5 由 planning/knowledge 宿主 @MapperScan 增补本包。
 * SQL 语句统一在 resources/mapper/AgentTraceMapper.xml（AD-1e 注解→XML，方法签名与语义逐字段不变）。</p>
 */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    /**
     * E-5b：窗口内逐轮（模型, 时长）明细（供服务层按模型聚合 P50/P95；
     * MySQL 8 无 PERCENTILE_CONT，百分位在 Java 侧计算）。
     */
    List<Map<String, Object>> selectModelDurationsSince(@Param("since") LocalDateTime since);

    /** E-5b：窗口内 Top N 慢轮次明细（duration_ms 降序）。 */
    List<Map<String, Object>> selectTopSlowTurns(@Param("since") LocalDateTime since);
}
