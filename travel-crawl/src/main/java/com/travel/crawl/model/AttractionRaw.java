package com.travel.crawl.model;

import com.travel.core.data.SourceConfidence;

import java.util.List;

/**
 * 爬虫规范产物模型（F110-B）：17 字段与 attractions_raw.json 对齐，扩展 poiId/置信度/多图/抓取时间。
 *
 * <p>序列化后新增字段会被 knowledge 导入忽略（JsonUtils 忽略未知属性），
 * 保留 17 个原始键保证既有文件/导入兼容。</p>
 */
public record AttractionRaw(
        String poiId,
        String name,
        String city,
        String district,
        String type,
        String description,
        Double lat,
        Double lng,
        String address,
        String openHours,
        Double ticketPrice,
        Integer freeEntry,
        Double rating,
        Integer ratingCount,
        String tags,
        String recommendedDuration,
        String imageUrl,
        String source,
        SourceConfidence confidence,
        List<String> imageUrls,
        String fetchedAt) {

    /** 兼容 17 字段构造（历史调用/测试），置信度默认 API */
    public AttractionRaw(String name, String city, String district, String type, String description,
                         Double lat, Double lng, String address, String openHours, Double ticketPrice,
                         Integer freeEntry, Double rating, Integer ratingCount, String tags,
                         String recommendedDuration, String imageUrl, String source) {
        this(null, name, city, district, type, description, lat, lng, address, openHours, ticketPrice,
                freeEntry, rating, ratingCount, tags, recommendedDuration, imageUrl, source,
                SourceConfidence.API, List.of(), "");
    }

    /** 18 参便捷构造（poiId + 17 字段），置信度默认 API */
    public AttractionRaw(String poiId, String name, String city, String district, String type,
                         String description, Double lat, Double lng, String address, String openHours,
                         Double ticketPrice, Integer freeEntry, Double rating, Integer ratingCount,
                         String tags, String recommendedDuration, String imageUrl, String source) {
        this(poiId, name, city, district, type, description, lat, lng, address, openHours,
                ticketPrice, freeEntry, rating, ratingCount, tags, recommendedDuration, imageUrl,
                source, SourceConfidence.API, List.of(), "");
    }
}
