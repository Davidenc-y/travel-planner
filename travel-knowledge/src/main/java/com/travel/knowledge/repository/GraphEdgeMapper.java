package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.GraphEdge;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * GraphRAG 边 MyBatis Mapper
 *
 * <p>SQL 语句统一在 resources/mapper/GraphEdgeMapper.xml（AG-1a：仅启动/日级刷新全量加载用，E-53 检索热路径零 IO）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface GraphEdgeMapper extends BaseMapper<GraphEdge> {

    /**
     * 全量边（启动/日级刷新加载用）
     */
    List<GraphEdge> selectAllEdges();
}
