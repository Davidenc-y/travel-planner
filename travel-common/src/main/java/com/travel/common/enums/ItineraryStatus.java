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

    /** M4-8：生成中（占位行已插入，工作流执行中；僵尸判定后可 resume） */
    GENERATING,

    /** M4-8：生成失败/超时（快照存在时可 resume 断点续跑） */
    FAILED,

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
