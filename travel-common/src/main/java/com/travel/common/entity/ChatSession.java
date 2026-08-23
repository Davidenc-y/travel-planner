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

    /**
     * M4-4：收口摘要持久层（会话归档时的全量重算摘要）。
     * 隐式待办语义：status=ARCHIVED 且 summary_final IS NULL 即"收口未完成"，
     * 启动补偿/P2 空闲扫描器据此补跑（R2 方案 1.1，替代队列组件）。
     */
    private String summaryFinal;
}
