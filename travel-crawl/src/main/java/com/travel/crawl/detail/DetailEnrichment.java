package com.travel.crawl.detail;

import com.travel.core.data.SourceConfidence;

import java.util.List;

/**
 * 详情补充产物（F115 T1）：补充源可提供的字段 + 来源 URL + 未命中字段 + 置信度。
 * 管道仅合并 description/lat/lng；其余字段保持空/人工补录（MANUAL）。
 */
public record DetailEnrichment(
        String description,
        Double lat,
        Double lng,
        String officialUrl,
        List<String> sourceUrls,
        List<String> notFoundFields,
        SourceConfidence confidence) {

    public static DetailEnrichment empty() {
        return new DetailEnrichment(null, null, null, null, List.of(), List.of(), SourceConfidence.ENRICH);
    }

    public boolean hasAny() {
        return !isBlank(description) || lat != null || lng != null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
