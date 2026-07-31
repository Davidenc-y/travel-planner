package com.travel.planning.service;

import com.travel.common.entity.TravelProfile;
import com.travel.common.util.JsonUtils;
import com.travel.planning.repository.TravelProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户旅游画像服务
 *
 * <p>管理用户旅游偏好（常去目的地、兴趣、预算区间、出行风格），
 * 支持行程生成后自动更新画像。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelProfileService {

    private final TravelProfileMapper profileMapper;

    /**
     * 获取用户画像（不存在则创建空画像）
     */
    public TravelProfile getByUserId(Long userId) {
        TravelProfile profile = profileMapper.findByUserId(userId);
        if (profile == null) {
            profile = new TravelProfile();
            profile.setUserId(userId);
            profile.setPreferredDestinations("[]");
            profile.setPreferredInterests("[]");
            profile.setBudgetRange("");
            profile.setTravelStyle("COMFORT");
            profile.setHistoryTrips("[]");
            profile.setTotalTrips(0);
            profileMapper.insert(profile);
            log.info("创建用户旅游画像: userId={}", userId);
        }
        return profile;
    }

    /**
     * 更新用户画像
     */
    public TravelProfile update(Long userId, String preferredDestinations,
                                 String preferredInterests, String budgetRange,
                                 String travelStyle) {
        TravelProfile profile = getByUserId(userId);
        if (preferredDestinations != null) profile.setPreferredDestinations(preferredDestinations);
        if (preferredInterests != null) profile.setPreferredInterests(preferredInterests);
        if (budgetRange != null) profile.setBudgetRange(budgetRange);
        if (travelStyle != null) profile.setTravelStyle(travelStyle);
        profileMapper.updateById(profile);
        log.info("更新用户旅游画像: userId={}", userId);
        return profile;
    }

    /**
     * 行程生成后自动更新画像（添加目的地 + 兴趣 + 历史行程）
     *
     * @param userId      用户 ID
     * @param destination 目的地
     * @param interests   兴趣列表 JSON
     * @param title       行程标题
     */
    public void recordTrip(Long userId, String destination, String interests, String title) {
        try {
            TravelProfile profile = getByUserId(userId);

            // 添加目的地（去重）
            List<String> destinations = JsonUtils.parseList(profile.getPreferredDestinations(), String.class);
            if (!destinations.contains(destination)) {
                destinations.add(destination);
                if (destinations.size() > 10) destinations.remove(0);  // 保留最近 10 个
            }
            profile.setPreferredDestinations(JsonUtils.toJson(destinations));

            // 添加兴趣（去重）
            List<String> currentInterests = JsonUtils.parseList(profile.getPreferredInterests(), String.class);
            List<String> newInterests = JsonUtils.parseList(interests, String.class);
            for (String interest : newInterests) {
                if (!currentInterests.contains(interest)) {
                    currentInterests.add(interest);
                }
            }
            profile.setPreferredInterests(JsonUtils.toJson(currentInterests));

            // 添加历史行程（保留最近 20 条）
            List<String> trips = JsonUtils.parseList(profile.getHistoryTrips(), String.class);
            trips.add(0, title);  // 最新的放前面
            if (trips.size() > 20) trips = new ArrayList<>(trips.subList(0, 20));
            profile.setHistoryTrips(JsonUtils.toJson(trips));

            // 更新行程计数
            profile.setTotalTrips(profile.getTotalTrips() + 1);

            profileMapper.updateById(profile);
            log.info("画像自动更新: userId={}, destination={}, totalTrips={}",
                    userId, destination, profile.getTotalTrips());
        } catch (Exception e) {
            log.warn("画像自动更新失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
    }
}
