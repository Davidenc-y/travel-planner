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
 * 聊天消息幂等登记实体（t_chat_message_idem，M4-3/P0-3）。
 *
 * <p>以客户端生成的 {@code clientMessageId}（UUID）为主键，登记一轮消息的
 * 处理状态：PENDING（进行中，重试返回 40904）/ COMPLETED（重放 assistant 响应）/
 * FAILED（路由兜底文案，重试重新执行不重放）。用户消息落库与 PENDING 登记
 * 同事务，杜绝"超时重试重复追加用户消息"（M4-0-R1 评审 D3-1）。</p>
 *
 * <p>独立表而非 t_chat_message 加列：消息表是 append-only 语义（F25，
 * ChatMessageTableInfoTest 守护无 updated_at），状态机不适合放消息表。</p>
 */
@Data
@TableName("t_chat_message_idem")
public class ChatMessageIdem implements Serializable {

    /** 状态：进行中（同键重试返回 40904） */
    public static final String STATUS_PENDING = "PENDING";
    /** 状态：已完成（同键重试重放 assistant 响应） */
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 状态：失败（路由兜底文案；同键重试重新执行，不重放兜底） */
    public static final String STATUS_FAILED = "FAILED";

    /** 客户端生成的幂等键（UUID），主键 */
    @TableId(type = IdType.INPUT)
    private String clientMessageId;

    /** 归属会话（幂等键跨会话复用视为非法） */
    private String sessionId;

    /** 已落库的用户消息 id（FAILED 重试时复用，不再重复追加） */
    private Long userMessageId;

    /** 关联的 assistant 消息 id（COMPLETED 时非空） */
    private Long assistantMessageId;

    /** PENDING / COMPLETED / FAILED */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
