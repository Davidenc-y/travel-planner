package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.GraphNode;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * GraphRAG 节点 MyBatis Mapper
 *
 * <p>SQL 语句统一在 resources/mapper/GraphNodeMapper.xml（AG-1a：三条 SELECT 仅启动/日级刷新全量加载用，E-53 检索热路径零 IO）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface GraphNodeMapper extends BaseMapper<GraphNode> {

    /**
     * 全量节点（启动/日级刷新加载用）
     */
    List<GraphNode> selectAllNodes();

    /**
     * 脏节点 id 列表（增量刷新触发判定用）
     */
    List<Long> selectDirtyNodeIds();

    /**
     * 清理脏标记（AG-1b 日级重建后维护位复位；仅 dirty 非空时被调用，运行时零实际写——change-scan 生产默认关）
     */
    int clearDirty(List<Long> ids);
}
