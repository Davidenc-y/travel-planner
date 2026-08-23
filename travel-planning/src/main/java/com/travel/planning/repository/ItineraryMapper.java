package com.travel.planning.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Itinerary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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

    /**
     * M4-7：幂等查询补 userId 条件（防跨用户命中他人 clientRequestId 占位行/行程）。
     */
    @Select("SELECT * FROM t_itinerary WHERE client_request_id = #{clientRequestId} AND user_id = #{userId}")
    Itinerary findByClientRequestIdAndUser(@Param("clientRequestId") String clientRequestId,
                                           @Param("userId") Long userId);

    @Select("SELECT * FROM t_itinerary WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    List<Itinerary> findByUserId(Long userId, int offset, int size);

    @Select("SELECT COUNT(*) FROM t_itinerary WHERE user_id = #{userId}")
    long countByUserId(Long userId);
}
