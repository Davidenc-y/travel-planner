package com.travel.planning.repository;

import com.travel.common.entity.UserModelUsage;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * AB-5b：用户模型使用计数 Mapper（t_user_model_usage）。
 *
 * <p>复合主键 (user_id, model_key, slot_id)、无自增 id——自定义 SQL、禁用 BaseMapper
 * 按主键通用方法。</p>
 *
 * <p><b>滚动均值赋值序陷阱</b>：MySQL {@code ON DUPLICATE KEY UPDATE} 赋值自左向右求值、
 * 后位赋值可见前位更新值——故 avg 赋值必须在前（此时 use_count 仍为旧值，权重正确：
 * new_avg = (old_avg*old_cnt + ttft) / (old_cnt+1)），use_count 自增在后；序颠倒则权重失真。
 * ttftMs=null 时不触碰既有均值；整数除法截断=滚动均值近似口径。</p>
 */
@Mapper
public interface UserModelUsageMapper {

    @Insert("INSERT INTO t_user_model_usage (user_id, model_key, slot_id, use_count, total_tokens, avg_ttft_ms) "
            + "VALUES (#{userId}, #{modelKey}, #{slotId}, 1, #{tokens}, #{ttftMs}) "
            + "ON DUPLICATE KEY UPDATE "
            + "avg_ttft_ms = CASE WHEN #{ttftMs} IS NULL THEN avg_ttft_ms "
            + "WHEN avg_ttft_ms IS NULL THEN #{ttftMs} "
            + "ELSE (avg_ttft_ms * use_count + #{ttftMs}) / (use_count + 1) END, "
            + "use_count = use_count + 1, "
            + "total_tokens = total_tokens + #{tokens}")
    int incrementModelUsage(@Param("userId") Long userId, @Param("modelKey") String modelKey,
                            @Param("slotId") int slotId, @Param("tokens") long tokens,
                            @Param("ttftMs") Integer ttftMs);

    /** 拉取用户全部模型使用行（含 slot_id=-1 汇总行；聚合在服务层，AB-5d） */
    @Select("SELECT user_id, model_key, slot_id, use_count, total_tokens, avg_ttft_ms, updated_at "
            + "FROM t_user_model_usage WHERE user_id = #{userId} ORDER BY slot_id, model_key")
    List<UserModelUsage> selectByUser(@Param("userId") Long userId);
}
