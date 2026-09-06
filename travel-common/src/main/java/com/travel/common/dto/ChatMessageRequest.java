package com.travel.common.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * M7 Batch 2（D8）：聊天消息请求 DTO，双传输栈（MVC/WebFlux）+ JSON/SSE 共用。
 *
 * <p>替换三处 {@code Map<String, String>} 手工解析；model 为可选请求级模型
 * （注册表 key，格式校验后由领域层 D6 校验存在性）。</p>
 */
@Data
public class ChatMessageRequest {

    @NotBlank(message = "消息不能为空")
    private String message;

    /** M4-3：消息幂等键（可选；不携带走原路径） */
    private String clientMessageId;

    /** M7：请求级模型 key（可选；null=角色默认） */
    @Pattern(regexp = "^[a-zA-Z0-9._-]{1,64}$", message = "模型参数非法")
    private String model;

    /** M23（E1）：消息内锚定快照（per-turn truth；≤3，单锚定首发由前端互斥约束）。 */
    @Size(max = 3, message = "锚定行程数量超限")
    private List<Long> anchoredItineraryIds;

    /** M23b（E4）：本轮偏好标签（结构化约束，不自动写长期画像）。 */
    @Valid
    private PreferenceTagsDTO preferences;
}
