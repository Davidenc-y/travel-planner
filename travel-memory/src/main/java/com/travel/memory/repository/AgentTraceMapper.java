package com.travel.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Agent 追溯 Mapper（t_agent_trace，F89） */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {

    /**
     * E-5b：窗口内逐轮（模型, 时长）明细（供服务层按模型聚合 P50/P95；
     * MySQL 8 无 PERCENTILE_CONT，百分位在 Java 侧计算）。
     */
    @Select("SELECT model_name AS modelName, duration_ms AS durationMs "
            + "FROM t_agent_trace "
            + "WHERE start_time >= #{since} AND duration_ms IS NOT NULL")
    List<Map<String, Object>> selectModelDurationsSince(@Param("since") LocalDateTime since);

    /** E-5b：窗口内 Top N 慢轮次明细（duration_ms 降序）。 */
    @Select("SELECT id, request_id AS requestId, model_name AS modelName, "
            + "duration_ms AS durationMs, endpoint, start_time AS startTime "
            + "FROM t_agent_trace "
            + "WHERE start_time >= #{since} AND duration_ms IS NOT NULL "
            + "ORDER BY duration_ms DESC LIMIT 10")
    List<Map<String, Object>> selectTopSlowTurns(@Param("since") LocalDateTime since);
}
