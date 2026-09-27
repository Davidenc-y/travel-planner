package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ItineraryTaskSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 行程任务节点快照 Mapper（M4-8/P1-5）。
 *
 * <p>SQL 语句统一在 resources/mapper/ItineraryTaskSnapshotMapper.xml（AD-1b 注解→XML，方法签名与语义逐字段不变）。
 */
@Mapper
public interface ItineraryTaskSnapshotMapper extends BaseMapper<ItineraryTaskSnapshot> {

    /** 按任务取全部快照（最新在前；调用方按 node 取每节点最新） */
    List<ItineraryTaskSnapshot> findByTaskId(@Param("taskId") Long taskId);

    /** resume 前清理该任务旧快照（重新执行被跳过节点前的兜底，幂等） */
    int deleteByTaskId(@Param("taskId") Long taskId);
}
