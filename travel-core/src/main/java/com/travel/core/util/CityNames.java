package com.travel.core.util;

/**
 * 城市名归一化（F108/F110-B 共享）：去首尾空格、去掉末尾"市"（直辖市/地级市短名），
 * 自治州/地区/盟等保留原样。
 */
public final class CityNames {

    private CityNames() {
    }

    public static String normalize(String city) {
        String c = city == null ? "" : city.trim();
        if (c.endsWith("市") && c.length() > 2) {
            return c.substring(0, c.length() - 1);
        }
        return c;
    }
}
