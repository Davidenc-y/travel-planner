package com.travel.common.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Data;

import java.util.List;

/**
 * M23b（E4）：会话内"偏好"标签 DTO——与原 /plan 表单字段同构（D-V8 切分声明）。
 *
 * <p>随消息体传输（per-turn truth，与锚定快照同批），后端确定性渲染
 * 【本轮偏好约束】段（优先级：本轮偏好标签 > 会话最新确认 > 锚定行程 > 长期画像）。
 * 显式标签<b>不自动写长期画像</b>（单次偏好≠长期偏好，避免画像污染）。</p>
 */
@Data
public class PreferenceTagsDTO {

    @Size(min = 2, max = 20, message = "目的地长度须在 2~20 之间")
    private String destination;

    @Min(value = 1, message = "天数至少 1 天")
    @Max(value = 30, message = "天数最多 30 天")
    private Integer days;

    @DecimalMin(value = "0", message = "预算不能为负")
    private java.math.BigDecimal budget;

    @Pattern(regexp = "独行|情侣|家庭|朋友", message = "同行人类型非法")
    private String party;

    @Size(max = 6, message = "兴趣标签最多 6 项")
    private List<String> interests;

    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$", message = "出发日期格式应为 yyyy-MM-dd")
    private String startDate;
}
