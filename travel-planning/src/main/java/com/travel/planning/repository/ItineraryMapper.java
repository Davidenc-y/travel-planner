package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Itinerary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 行程 Mapper
 *
 * @author 吴八哥
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface ItineraryMapper extends BaseMapper<Itinerary> {

    @Select("SELECT * FROM t_itinerary WHERE client_request_id = #{clientRequestId}")
    Itinerary findByClientRequestId(String clientRequestId);

    @Select("SELECT * FROM t_itinerary WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    List<Itinerary> findByUserId(Long userId, int offset, int size);

    @Select("SELECT COUNT(*) FROM t_itinerary WHERE user_id = #{userId}")
    long countByUserId(Long userId);
}
