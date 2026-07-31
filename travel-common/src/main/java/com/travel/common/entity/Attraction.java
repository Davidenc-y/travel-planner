package com.travel.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 景点实体（t_attraction）
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_attraction")
public class Attraction extends BaseEntity {

    /** 景点名称 */
    private String name;

    /** 城市 */
    private String city;

    /** 区县 */
    private String district;

    /** 类型: CULTURE/NATURE/FOOD/SHOPPING/FAMILY/LEISURE */
    private String type;

    /** 详细描述 */
    private String description;

    /** 纬度 */
    private Double lat;

    /** 经度 */
    private Double lng;

    /** 详细地址 */
    private String address;

    /** 开放时间 */
    private String openHours;

    /** 门票价格 */
    private BigDecimal ticketPrice;

    /** 是否免费: 0=否, 1=是 */
    private Integer freeEntry;

    /** 评分 0-5 */
    private BigDecimal rating;

    /** 评分人数 */
    private Integer ratingCount;

    /** JSON 数组: ["文化","历史"] */
    private String tags;

    /** 推荐时长: 3-4小时 */
    private String recommendedDuration;

    /** 图片 OSS URL */
    private String imageUrl;

    /** 来源: ctrip/mafengwo/manual */
    private String source;

    /** 是否已索引到 Milvus/ES */
    private Integer indexed;
}
