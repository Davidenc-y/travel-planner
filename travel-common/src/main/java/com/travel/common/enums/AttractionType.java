package com.travel.common.enums;

import lombok.Getter;

/**
 * 景点类型枚举
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Getter
public enum AttractionType {

    CULTURE("文化"),
    NATURE("自然"),
    FOOD("美食"),
    SHOPPING("购物"),
    FAMILY("亲子"),
    LEISURE("休闲");

    private final String label;

    AttractionType(String label) {
        this.label = label;
    }

    /**
     * 从中文名称解析枚举
     */
    public static AttractionType fromLabel(String label) {
        if (label == null) {
            return LEISURE;
        }
        for (AttractionType t : values()) {
            if (t.label.equals(label) || t.name().equalsIgnoreCase(label)) {
                return t;
            }
        }
        return LEISURE;
    }
}
