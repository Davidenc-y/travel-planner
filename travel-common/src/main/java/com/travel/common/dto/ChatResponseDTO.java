package com.travel.common.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 聊天响应 DTO
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@Builder
public class ChatResponseDTO {

    private String sessionId;
    private String response;
    private Long itineraryId;
    private Integer tokens;
}
