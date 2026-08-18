package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;

/** Agent 追溯 Mapper（t_agent_trace，F89；knowledge 侧 RAG 链路写入） */
@Mapper
public interface AgentTraceMapper extends BaseMapper<AgentTrace> {
}
