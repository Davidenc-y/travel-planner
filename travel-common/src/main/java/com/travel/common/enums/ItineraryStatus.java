package com.travel.common.enums;

/**
 * 行程状态枚举
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
public enum ItineraryStatus {

    /** 草稿 */
    DRAFT,

    /** 已生成 */
    GENERATED,

    /** 已确认 */
    CONFIRMED,

    /** 已归档 */
    ARCHIVED;

    /**
     * 从字符串解析枚举（不区分大小写，异常返回 DRAFT）
     */
    public static ItineraryStatus fromString(String s) {
        if (s == null) {
            return DRAFT;
        }
        try {
            return ItineraryStatus.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return DRAFT;
        }
    }
}
