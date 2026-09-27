package com.travel.planning.repository;

import com.travel.common.entity.UserModelUsage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
 *
 * <p>SQL 语句统一在 resources/mapper/UserModelUsageMapper.xml（AD-1c 注解→XML，
 * 方法签名与语义逐字段不变，赋值序陷阱原文同步迁移至 XML 注释）。</p>
 */
@Mapper
public interface UserModelUsageMapper {

    int incrementModelUsage(@Param("userId") Long userId, @Param("modelKey") String modelKey,
                            @Param("slotId") int slotId, @Param("tokens") long tokens,
                            @Param("ttftMs") Integer ttftMs);

    /** 拉取用户全部模型使用行（含 slot_id=-1 汇总行；聚合在服务层，AB-5d） */
    List<UserModelUsage> selectByUser(@Param("userId") Long userId);
}
