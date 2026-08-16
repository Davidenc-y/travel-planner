package com.travel.planning.memory.longterm;

import com.travel.common.entity.TravelProfile;

import java.math.BigDecimal;

/**
 * 长期用户画像端口（F50/Phase A）。
 *
 * <p>画像读写与业务解耦；当前由 {@code TravelProfileService} 实现，
 * 未来拆分 travel-memory 时仅迁移实现。</p>
 */
public interface ProfilePort {

    /**
     * 获取用户画像（不存在则创建空画像）
     */
    TravelProfile getOrCreate(Long userId);

    /**
     * 行程生成后自动更新画像（添加目的地 + 兴趣 + 历史行程）
     */
    void recordTrip(Long userId, String destination, String interests, String title,
                    BigDecimal budget, String party);

    /**
     * 更新画像偏好字段（F64/B2：画像 Tool 用），返回更新后的画像
     */
    TravelProfile update(Long userId, String preferredDestinations, String preferredInterests,
                         String budgetRange, String travelStyle);
}
