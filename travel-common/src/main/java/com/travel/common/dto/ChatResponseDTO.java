package com.travel.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天响应 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponseDTO {

    private String sessionId;
    private String response;
    private Long itineraryId;
    private Integer tokens;
    /** M5-1：本次发送后会话标题（首条消息自动生成时返回；其余场景为 null） */
    private String sessionTitle;
}
