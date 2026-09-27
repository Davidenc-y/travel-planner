package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 *
 * <p>SQL 语句统一在 resources/mapper/UserMapper.xml（AD-1c 注解→XML，方法签名与语义逐字段不变）。
 *
 * @author 吴八哥
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    User findByUsername(String username);

    /** M5-1：按邮箱查询用户（绑定邮箱唯一性校验） */
    User findByEmail(String email);
}
