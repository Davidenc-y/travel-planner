package com.travel.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 聊天请求 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
public class ChatRequestDTO {

    /** 会话 ID */
    @NotBlank(message = "sessionId 不能为空")
    private String sessionId;

    /** 消息内容 */
    @NotBlank(message = "消息内容不能为空")
    private String message;

    /** 是否流式返回（默认 false） */
    private Boolean stream = false;
}
