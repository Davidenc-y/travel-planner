package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息实体（t_chat_message）
 *
 * <p>聊天记录为 append-only，表结构无 {@code updated_at} 列，因此本实体不继承
 * {@link BaseEntity}，避免 MyBatis-Plus 自动填充 {@code updatedAt} 导致 INSERT
 * 引用不存在的列（TC-10 50000 报错根因，见 M2-4-F25）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@TableName("t_chat_message")
public class ChatMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 会话 ID */
    private String sessionId;

    /** 角色: user/assistant/system */
    private String role;

    /** 消息内容 */
    private String content;

    /** token 消耗 */
    private Integer tokens;
}
