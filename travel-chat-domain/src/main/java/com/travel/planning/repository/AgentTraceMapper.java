package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;

/** Agent 追溯 Mapper（t_agent_trace，F89） */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {
}
