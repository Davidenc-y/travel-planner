package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.TravelProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * SQL 语句统一在 resources/mapper/TravelProfileMapper.xml（AD-1d 注解→XML，方法签名与语义逐字段不变）。
 */
@Mapper
public interface TravelProfileMapper extends BaseMapper<TravelProfile> {

    TravelProfile findByUserId(Long userId);
}
