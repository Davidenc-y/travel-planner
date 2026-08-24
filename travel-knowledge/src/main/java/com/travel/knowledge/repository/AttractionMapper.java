package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Attraction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 景点 MyBatis Mapper
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface AttractionMapper extends BaseMapper<Attraction> {

    /**
     * 查询未索引的景点（indexed=0）
     */
    @Select("SELECT * FROM t_attraction WHERE indexed = 0 ORDER BY id LIMIT #{limit}")
    List<Attraction> findUnindexed(int limit);

    /**
     * 标记景点已索引
     */
    @Update("UPDATE t_attraction SET indexed = 1 WHERE id = #{id}")
    int markIndexed(Long id);

    /**
     * 按城市查询景点
     */
    @Select("SELECT * FROM t_attraction WHERE city = #{city} ORDER BY rating DESC")
    List<Attraction> findByCity(String city);

    /**
     * 统计景点总数
     */
    @Select("SELECT COUNT(*) FROM t_attraction")
    long countAll();

    /**
     * 统计已索引数
     */
    @Select("SELECT COUNT(*) FROM t_attraction WHERE indexed = 1")
    long countIndexed();

    /** M5-1：全部城市去重列表（景点“浏览全部”下拉数据源） */
    @Select("SELECT DISTINCT city FROM t_attraction ORDER BY city")
    List<String> listCities();
}
