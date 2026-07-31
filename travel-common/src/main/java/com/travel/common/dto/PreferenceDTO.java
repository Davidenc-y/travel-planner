package com.travel.common.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 偏好数据传输对象
 *
 * <p>由 PreferenceAnalysisAgent 从用户输入中提取，供后续 Agent 使用。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
public class PreferenceDTO {

    private String destination;
    private Integer days;
    private BigDecimal budget;
    private List<String> interests;
    private String party;
    private String travelStyle;
    private List<String> specialNeeds;
}
