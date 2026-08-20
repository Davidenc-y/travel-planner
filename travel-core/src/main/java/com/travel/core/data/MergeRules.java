package com.travel.core.data;

/**
 * 字段合并策略（F110-B）：incoming 非空 且 置信度不低于 existing 时覆盖；
 * 否则保留 existing。null 安全。
 */
public final class MergeRules {

    private MergeRules() {
    }

    public static <T> T choose(T existing, SourceConfidence existingConf,
                               T incoming, SourceConfidence incomingConf) {
        if (incoming == null) {
            return existing;
        }
        if (existing == null) {
            return incoming;
        }
        return incomingConf.level() >= existingConf.level() ? incoming : existing;
    }
}
