package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Itinerary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 行程 Mapper
 *
 * <p>SQL 语句统一在 resources/mapper/ItineraryMapper.xml（AD-1b 注解→XML，方法签名与语义逐字段不变）。
 *
 * @author 吴八哥
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface ItineraryMapper extends BaseMapper<Itinerary> {

    /**
     * M4-7：幂等查询补 userId 条件（防跨用户命中他人 clientRequestId 占位行/行程）。
     */
    Itinerary findByClientRequestIdAndUser(@Param("clientRequestId") String clientRequestId,
                                           @Param("userId") Long userId);

    List<Itinerary> findByUserId(Long userId, int offset, int size);

    long countByUserId(Long userId);
}
