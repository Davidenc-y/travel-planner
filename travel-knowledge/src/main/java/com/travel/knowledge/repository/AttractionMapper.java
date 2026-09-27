package com.travel.knowledge.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travel.common.entity.Attraction;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 景点 MyBatis Mapper
 *
 * <p>SQL 语句统一在 resources/mapper/AttractionMapper.xml（AD-1a 注解→XML，方法签名与语义逐字段不变）。
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Mapper
public interface AttractionMapper extends BaseMapper<Attraction> {

    /**
     * 查询未索引的景点（indexed=0）
     */
    List<Attraction> findUnindexed(int limit);

    /**
     * 标记景点已索引
     */
    int markIndexed(Long id);

    /**
     * 按城市查询景点
     */
    List<Attraction> findByCity(String city);

    /**
     * 统计景点总数
     */
    long countAll();

    /**
     * 统计已索引数
     */
    long countIndexed();

    /** M5-1：全部城市去重列表（景点“浏览全部”下拉数据源） */
    List<String> listCities();
}
