package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户时段画像实体（t_user_profile_slot，AB-5a）
 *
 * <p>复合主键 (user_id, slot_id)、无自增 id——<b>不继承</b> BaseEntity（其 {@code @TableId(AUTO)}
 * 会映射不存在的 id 列）；消费方 Mapper（AB-5b ProfileSlotMapper）须用自定义 SQL
 * （@Insert ... ON DUPLICATE KEY UPDATE / @Select），禁用 BaseMapper 按主键通用方法。
 * JSON 列以字符串承载，序列化/聚合在服务层。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@TableName("t_user_profile_slot")
public class ProfileSlot implements Serializable {

    /** 用户 ID（复合主键之一） */
    private Long userId;

    /** 时段编号 0~5（每 4 小时一段，slot = hour / 4） */
    private Integer slotId;

    /** 该时段累计使用次数 */
    private Integer useCount;

    /** 该时段高频景点标签聚合 JSON，格式 [{"tag":"文化","cnt":12},...] */
    private String preferredTags;

    /** 该时段常用模型聚合 JSON，格式 [{"model":"qwen3.7-max","cnt":8},...] */
    private String preferredModels;

    /** 最后更新时间（DB ON UPDATE CURRENT_TIMESTAMP 维护） */
    private LocalDateTime updatedAt;
}
