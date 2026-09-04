package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Attraction;
import org.apache.ibatis.annotations.Mapper;

/**
 * M11-2：行程详情坐标回查专用 Mapper（只读 t_attraction 的 lat/lng）。
 */
@Mapper
public interface AttractionCoordinateLookupMapper extends BaseMapper<Attraction> {
}
