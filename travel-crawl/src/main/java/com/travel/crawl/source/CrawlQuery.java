package com.travel.crawl.source;

/** 抓取查询（F110-B）：城市+adcode（文本搜索）或坐标（周边搜索） */
public record CrawlQuery(String city, String adcode, String location, String types) {

    public static CrawlQuery ofCity(String city, String adcode, String types) {
        return new CrawlQuery(city, adcode, null, types);
    }

    public static CrawlQuery ofLocation(String location, String types) {
        return new CrawlQuery(null, null, location, types);
    }

    public String region() {
        return adcode != null && !adcode.isBlank() ? adcode : city;
    }
}
