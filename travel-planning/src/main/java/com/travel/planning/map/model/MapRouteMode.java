package com.travel.planning.map.model;

/**
 * 路线规划模式（M12）。
 *
 * <p>UNSUPPORTED 表示公交/地铁等本版本不调用方向 API 的模式，
 * 前端以示意线降级，避免乱用免费配额。</p>
 */
public enum MapRouteMode {
    WALKING,
    DRIVING,
    UNSUPPORTED
}
