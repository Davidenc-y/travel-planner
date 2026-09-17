package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.UserBehaviorProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * M17-2：行为画像 Mapper（chat-domain 仓储）。
 */
@Mapper
public interface UserBehaviorProfileMapper extends BaseMapper<UserBehaviorProfile> {

    @Select("SELECT * FROM t_user_behavior_profile WHERE user_id = #{userId}")
    UserBehaviorProfile findByUserId(Long userId);
}
