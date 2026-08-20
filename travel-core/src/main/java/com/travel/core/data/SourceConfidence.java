package com.travel.core.data;

/**
 * 字段来源置信度（F110-B）：MANUAL > ENRICH > API。
 * 合并策略：新值非空且来源置信度不低于现有值时覆盖。
 */
public enum SourceConfidence {
    API(1),
    ENRICH(2),
    MANUAL(3);

    private final int level;

    SourceConfidence(int level) {
        this.level = level;
    }

    public int level() {
        return level;
    }

    /** 由落库 source 字段映射（manual/enrich 之外的来源按 API 级处理） */
    public static SourceConfidence ofSource(String source) {
        if (source == null) {
            return API;
        }
        String s = source.trim().toLowerCase();
        return switch (s) {
            case "manual" -> MANUAL;
            case "enrich" -> ENRICH;
            default -> API;
        };
    }
}
