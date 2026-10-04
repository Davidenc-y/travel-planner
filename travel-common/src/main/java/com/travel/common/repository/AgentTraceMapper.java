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
     * AX-3 后由聚合查询取代，保留兼容。
     */
    List<Map<String, Object>> selectModelDurationsSince(@Param("since") LocalDateTime since);

    /** E-5b：窗口内 Top N 慢轮次明细（duration_ms 降序）。 */
    List<Map<String, Object>> selectTopSlowTurns(@Param("since") LocalDateTime since);

    /** AX-2：stats 标量五合一聚合（单行返回；COUNT/AVG 忽略 NULL 与 Java 逐行收集语义一致）。 */
    Map<String, Object> selectStatsAggregateSince(@Param("since") LocalDateTime since);

    /** AX-2：按日 token/trace 计数（Java 侧按日补零拼趋势）。 */
    List<Map<String, Object>> selectDailyTokensSince(@Param("since") LocalDateTime since);

    /** AX-2：模型×状态分组计数。 */
    List<Map<String, Object>> selectModelStatusSince(@Param("since") LocalDateTime since);

    /** AX-2：grounding 精确百分位行（stats round 式秩；至多 2 行）。 */
    List<Map<String, Object>> selectGroundingPercentilesSince(@Param("since") LocalDateTime since);

    /**
     * AX 审计直修：窗口内 (model, duration) 对（created_at 口径，含 &gt;=0 过滤与 stats
     * 收集条件一致）——duration 分区窗口函数实测 2.5s 净回归，百分位回 Java 精确计算。
     */
    List<Map<String, Object>> selectModelDurationsByCreatedAtSince(@Param("since") LocalDateTime since);
}
