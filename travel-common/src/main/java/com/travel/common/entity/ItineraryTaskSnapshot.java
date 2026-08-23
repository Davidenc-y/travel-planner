package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 行程任务节点快照实体（t_itinerary_task_snapshot，M4-8/P1-5）。
 *
 * <p>关键产物节点（preference/attractions/routePlan/budgetEstimate）执行后落快照，
 * resume 时按"最新快照集"确定断点续跑（R2 G1/G2：轻量状态机，不依赖框架
 * checkpoint 序列化）。payload 一律为归一化后的业务 JSON 文本——
 * Optional 解包 / AssistantMessage 取 text / GraphResponse 置空（D1/F84 教训）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_itinerary_task_snapshot")
public class ItineraryTaskSnapshot extends BaseEntity {

    /** 关联 t_itinerary.id（GENERATING 占位行） */
    private Long taskId;

    /** 节点名：preference_analysis / attraction_filter / route_arrangement / budget_estimation */
    private String node;

    /** 归一化业务 JSON 文本（节点输出） */
    private String payload;
}
