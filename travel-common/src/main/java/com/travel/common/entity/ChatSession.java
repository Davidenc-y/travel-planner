package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 聊天会话实体（t_chat_session）
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_chat_session")
public class ChatSession extends BaseEntity {

    /** UUID */
    private String sessionId;

    /** 用户 ID */
    private Long userId;

    /** 会话标题 */
    private String title;

    /** 状态: ACTIVE/ARCHIVED */
    private String status;
}
