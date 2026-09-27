package com.travel.planning.repository;

import com.travel.common.entity.ProfileSlot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AB-5b：用户时段画像 Mapper（t_user_profile_slot）。
 *
 * <p>复合主键 (user_id, slot_id)、无自增 id——实体 javadoc 明令：<b>自定义 SQL、
 * 禁用 BaseMapper 按主键通用方法</b>（@MapperScan 已覆盖本包）。</p>
 *
 * <p>SQL 语句统一在 resources/mapper/ProfileSlotMapper.xml（AD-1c 注解→XML，方法签名与语义逐字段不变）。</p>
 */
@Mapper
public interface ProfileSlotMapper {

    /** 计数 upsert：行不存在插入（use_count=1），存在则 use_count+1（updated_at 由 DB ON UPDATE 维护） */
    int upsertSlotUsage(@Param("userId") Long userId, @Param("slotId") int slotId);

    /** 拉取用户全部时段行（聚合/零填充在服务层，AB-5d） */
    List<ProfileSlot> selectByUser(@Param("userId") Long userId);

    /** AC-1c（L16）：聚合写前确保行在（零计数行，不污染 use_count 语义；已存在=无操作） */
    int ensureRow(@Param("userId") Long userId, @Param("slotId") int slotId);

    /** AC-1c（L16）：聚合列整行回写（两列成对透传，未涉及侧原值保持） */
    int updateAggregates(@Param("userId") Long userId, @Param("slotId") int slotId,
                         @Param("tags") String tags, @Param("models") String models);
}
