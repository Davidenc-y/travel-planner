package com.travel.planning.repository;

import com.travel.common.entity.ProfileSlot;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AB-5b：用户时段画像 Mapper（t_user_profile_slot）。
 *
 * <p>复合主键 (user_id, slot_id)、无自增 id——实体 javadoc 明令：<b>自定义 SQL、
 * 禁用 BaseMapper 按主键通用方法</b>（@MapperScan 已覆盖本包）。</p>
 */
@Mapper
public interface ProfileSlotMapper {

    /** 计数 upsert：行不存在插入（use_count=1），存在则 use_count+1（updated_at 由 DB ON UPDATE 维护） */
    @Insert("INSERT INTO t_user_profile_slot (user_id, slot_id, use_count) "
            + "VALUES (#{userId}, #{slotId}, 1) "
            + "ON DUPLICATE KEY UPDATE use_count = use_count + 1")
    int upsertSlotUsage(@Param("userId") Long userId, @Param("slotId") int slotId);

    /** 拉取用户全部时段行（聚合/零填充在服务层，AB-5d） */
    @Select("SELECT user_id, slot_id, use_count, preferred_tags, preferred_models, updated_at "
            + "FROM t_user_profile_slot WHERE user_id = #{userId} ORDER BY slot_id")
    List<ProfileSlot> selectByUser(@Param("userId") Long userId);
}
