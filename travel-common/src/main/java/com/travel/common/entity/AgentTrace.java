package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Agent 调用追溯实体（t_agent_trace，F89）。
 *
 * <p>记录每次 Agent/LLM 调用链路：模型名、调用路径 [a,b,c]、接口、耗时、token、
 * 状态。两个服务（planning/knowledge）共用本表，各自通过独立 Mapper 写入。</p>
 */
@Data
@TableName("t_agent_trace")
public class AgentTrace implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请求ID（RunnableConfig.metadata 透传） */
    private String requestId;

    private Long userId;

    private String sessionId;

    /** chat / itinerary / rag / background */
    private String traceType;

    /** 调用接口，如 POST /api/v1/chat/sessions/{id}/messages */
    private String endpoint;

    /** 主模型名（qwen3.7-max 等） */
    private String modelName;

    /** 调用路径 JSON：["supervisor","preference_analysis",...] */
    private String callPath;

    /** 调用工具 JSON：["attraction_search",...] */
    private String tools;

    /** 输入摘要（截断 500） */
    private String inputSummary;

    private Integer outputLength;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Long durationMs;

    private Integer tokenTotal;

    private Integer tokenPrompt;

    private Integer tokenCompletion;

    /** RUNNING / SUCCESS / FAILED / TIMEOUT / SKIPPED */
    private String status;

    /** M8-2：生成端引用校验通过率（0~1；null=未校验/无景点输出） */
    private Double groundingRate;

    /** M8-2：未命中候选集的景点名 JSON 数组（空=全部有据；null=未校验） */
    private String groundingUnmatched;

    /** M8-6：REFINE 保留性校验通过率（0~1；null=未校验/非 REFINE） */
    private Double retentionRate;

    /** M8-6：静默丢失景点名 JSON 数组（null=未校验） */
    private String retentionLost;

    private String errorMsg;

    private LocalDateTime createdAt;
}
