package com.travel.common.dto;

import java.util.Map;

/**
 * 偏好词表单源（重构 R1.2）：party 规范词表与兴趣词表全后端唯一定义处。
 *
 * <p>自 SessionFactConsolidator.PARTY_CANONICAL 与
 * ExplicitInputParser.PARTY_CANONICAL_WORDS 剪切合并而来（两份词表人工预核
 * 13 个键值对完全一致，键值内容与合并前逐字符相同），原两处定义改为引用本类。</p>
 */
public final class PreferenceVocabulary {

    private PreferenceVocabulary() {
    }

    /**
     * M28-10：同行人规范值映射。
     * 词表未覆盖的写法（如“2人/3人”）返回命中词本身，保守不臆造。
     */
    public static final Map<String, String> PARTY_CANONICAL = Map.ofEntries(
            Map.entry("带小孩", "家庭"), Map.entry("带娃", "家庭"), Map.entry("亲子", "家庭"),
            Map.entry("家庭", "家庭"), Map.entry("家人", "家庭"),
            Map.entry("情侣", "情侣"), Map.entry("夫妻", "情侣"), Map.entry("两个人", "情侣"),
            Map.entry("朋友", "朋友"), Map.entry("闺蜜", "朋友"), Map.entry("同事", "朋友"),
            Map.entry("独行", "独行"), Map.entry("一个人", "独行"));

    /** M28-12：兴趣词（与前端 INTEREST_OPTIONS 同款），消费方逐词 contains 命中。 */
    public static final String[] INTEREST_WORDS = {"文化", "自然", "美食", "购物", "亲子", "休闲"};
}
