package com.travel.common.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 行程生成请求 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
public class ItineraryGenerateRequestDTO {

    /** 目的地（必填） */
    @NotBlank(message = "目的地不能为空")
    private String destination;

    /** 天数（必填，1-30） */
    @NotNull(message = "天数不能为空")
    @Min(value = 1, message = "天数最少 1 天")
    @Max(value = 30, message = "天数最多 30 天")
    private Integer days;

    /** 预算（选填） */
    private BigDecimal budget;

    /** 兴趣标签：文化/自然/美食/购物/亲子/休闲 */
    private List<String> interests;

    /** 出行人员: 独行/情侣/家庭/朋友 */
    private String party;

    /** 开始日期 yyyy-MM-dd */
    private String startDate;

    /** 幂等键 UUID（必填） */
    @NotBlank(message = "clientRequestId 不能为空")
    private String clientRequestId;

    /** Phase C/F78：关联会话 ID（可选；存在时行程知识写入该会话的 session_context） */
    private String sessionId;
}
