package com.travel.common.dto;

/**
 * M7 Batch 2：前端模型清单条目（GET /api/v1/models）。
 */
public record ModelOptionDTO(String key, String displayName, String provider, boolean selectable) {
}
