package com.travel.memory.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.UserBehaviorProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * M17-2：行为画像 Mapper（chat-domain 仓储）。
 *
 * <p>SQL 语句统一在 resources/mapper/UserBehaviorProfileMapper.xml（AD-1f 注解→XML，方法签名与语义逐字段不变）。
 */
@Mapper
public interface UserBehaviorProfileMapper extends BaseMapper<UserBehaviorProfile> {

    UserBehaviorProfile findByUserId(Long userId);
}
