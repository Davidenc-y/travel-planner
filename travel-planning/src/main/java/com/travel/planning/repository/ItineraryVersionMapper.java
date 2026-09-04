package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.ItineraryVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * M11-1：行程历史版本 Mapper。
 */
@Mapper
public interface ItineraryVersionMapper extends BaseMapper<ItineraryVersion> {
}
