package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户模型使用计数实体（t_user_model_usage，AB-5a）
 *
 * <p>复合主键 (user_id, model_key, slot_id)、无自增 id——<b>不继承</b> BaseEntity（同
 * {@link ProfileSlot} 理由）；slot_id=-1 表示不限时段的汇总行。消费方 Mapper（AB-5b
 * UserModelUsageMapper）须用自定义 SQL，禁用 BaseMapper 按主键通用方法。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@TableName("t_user_model_usage")
public class UserModelUsage implements Serializable {

    /** 用户 ID（复合主键之一） */
    private Long userId;

    /** 模型标识（模型注册表 key，如 qwen3.7-max） */
    private String modelKey;

    /** 时段编号（-1=不限时段汇总行） */
    private Integer slotId;

    /** 该模型该时段使用次数 */
    private Integer useCount;

    /** 累计消耗 token 数 */
    private Long totalTokens;

    /** 平均首 token 延迟毫秒（滚动均值） */
    private Integer avgTtftMs;

    /** 最后更新时间（DB ON UPDATE CURRENT_TIMESTAMP 维护） */
    private LocalDateTime updatedAt;
}
