package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 用户 Mapper
 *
 * @author 吴八哥
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM t_user WHERE username = #{username} AND deleted = 0")
    User findByUsername(String username);

    /** M5-1：按邮箱查询用户（绑定邮箱唯一性校验） */
    @Select("SELECT * FROM t_user WHERE email = #{email} AND deleted = 0 LIMIT 1")
    User findByEmail(String email);
}
