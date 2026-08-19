package com.travel.crawl.model;

/**
 * 爬虫产物条目（与 attractions_raw.json 17 字段对齐，camelCase）。
 */
public record CrawlItem(
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
        String source) {
}
