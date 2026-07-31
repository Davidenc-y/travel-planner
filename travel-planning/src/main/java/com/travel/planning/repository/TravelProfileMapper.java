package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.TravelProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TravelProfileMapper extends BaseMapper<TravelProfile> {

    @Select("SELECT * FROM t_travel_profile WHERE user_id = #{userId}")
    TravelProfile findByUserId(Long userId);
}
