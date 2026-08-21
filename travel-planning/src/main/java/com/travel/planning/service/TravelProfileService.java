package com.travel.planning.service;

import com.travel.common.entity.TravelProfile;
import com.travel.common.exception.BusinessException;
import com.travel.common.util.JsonUtils;
import com.travel.planning.config.LlmGovernor;
import com.travel.planning.memory.longterm.ProfilePort;
import com.travel.planning.prompt.PromptTemplates;
import com.travel.planning.repository.TravelProfileMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
public class TravelProfileService implements ProfilePort {

    /** 历史行程条目数超阈值触发 LLM 压缩（F64/B2） */
    private static final int HISTORY_COMPACT_THRESHOLD = 10;
    /** 历史行程压缩后文本长度上限 */
    private static final int HISTORY_COMPACT_MAX_CHARS = 200;
    /** B3-4/F72：偏好兴趣上限（确定性保存/工具/recordTrip 统一收口） */
    private static final int MAX_INTERESTS = 20;
    /** B3-4/F72：常去目的地上限（与 recordTrip 最近 10 个一致） */
    private static final int MAX_DESTINATIONS = 10;

    /** F69/B3-3：按 userId 串行化的条带锁（64 条，可重入；无需 schema 变更） */
    private static final int PROFILE_LOCK_STRIPES = 64;
    private final Object[] profileLocks = new Object[PROFILE_LOCK_STRIPES];

    {
        for (int i = 0; i < PROFILE_LOCK_STRIPES; i++) {
            profileLocks[i] = new Object();
        }
    }

    private final TravelProfileMapper profileMapper;
    private final ChatModel chatModel;
    // F75/B3-5：LLM 调用统一治理（画像压缩纳入并发许可）
    private final LlmGovernor llmGovernor;
    // M3-20：Prompt 模板外置（P1-17）
    private final PromptTemplates promptTemplates;

    /**
     * 获取用户画像（不存在则创建空画像）
     */
    public TravelProfile getByUserId(Long userId) {
        // F52：防御脏 userId，避免以 user_id=0 新建垃圾画像。
        if (userId == null || userId <= 0) {
            throw new BusinessException(40101, "用户未登录");
        }
        // F69/B3-3：读-改-写整段按 userId 串行化（可重入），避免并发写同一画像行 lost update
        synchronized (lockFor(userId)) {
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
    }

    /**
     * ProfilePort 实现：委托 getByUserId（不存在则创建空画像）
     */
    @Override
    public TravelProfile getOrCreate(Long userId) {
        return getByUserId(userId);
    }

    /**
     * 更新用户画像
     */
    @Override
    public TravelProfile update(Long userId, String preferredDestinations,
                                String preferredInterests, String budgetRange,
                                String travelStyle) {
        // F69/B3-3：画像写入口 1（save_user_profile）按 userId 串行化
        synchronized (lockFor(userId)) {
            TravelProfile profile = getByUserId(userId);
            // F70：字面量 "null"/空串视为"未提及"，避免 LLM 输出字符串 "null" 覆盖原值；
            //      列表字段改为"合并去重"而非整体替换（新增兴趣/目的地不丢失旧值）。
            if (isPresent(preferredInterests)) {
                profile.setPreferredInterests(
                        mergeJsonList(profile.getPreferredInterests(), preferredInterests, MAX_INTERESTS));
            }
            if (isPresent(preferredDestinations)) {
                profile.setPreferredDestinations(
                        mergeJsonList(profile.getPreferredDestinations(), preferredDestinations, MAX_DESTINATIONS));
            }
            if (isPresent(budgetRange)) {
                profile.setBudgetRange(budgetRange.trim());
            }
            if (isPresent(travelStyle)) {
                String style = travelStyle.trim();
                if (isValidTravelStyle(style)) {
                    profile.setTravelStyle(style);
                } else {
                    log.warn("忽略非法 travelStyle: {}", style);
                }
            }
            // F53：显式刷新 updated_at（updateById 会把实体旧值写回，覆盖 DB ON UPDATE）
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.updateById(profile);
            log.info("更新用户旅游画像: userId={}", userId);
            return profile;
        }
    }

    /**
     * 行程生成后自动更新画像（添加目的地 + 兴趣 + 历史行程）
     *
     * @param userId      用户 ID
     * @param destination 目的地
     * @param interests   兴趣列表 JSON
     * @param title       行程标题
     */
    @Override
    public void recordTrip(Long userId, String destination, String interests, String title,
                           BigDecimal budget, String party) {
        // F69/B3-3：画像写入口 2（行程生成）按 userId 串行化
        synchronized (lockFor(userId)) {
        try {
            TravelProfile profile = getByUserId(userId);

            // F53：预算区间与出行风格随行程更新（此前从未更新）。
            if (budget != null) {
                profile.setBudgetRange(budget + "元");
            }
            profile.setTravelStyle(mapTravelStyle(party));

            // 添加目的地（去重）
            List<String> destinations = JsonUtils.parseList(profile.getPreferredDestinations(), String.class);
            if (!destinations.contains(destination)) {
                destinations.add(destination);
                if (destinations.size() > MAX_DESTINATIONS) destinations.remove(0);  // 保留最近 10 个
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
            // B3-4/F72：兴趣上限（保留最近 MAX_INTERESTS 个，防止无界膨胀）
            if (currentInterests.size() > MAX_INTERESTS) {
                currentInterests = new ArrayList<>(
                        currentInterests.subList(currentInterests.size() - MAX_INTERESTS, currentInterests.size()));
            }
            profile.setPreferredInterests(JsonUtils.toJson(currentInterests));

            // F53：历史行程按标题去重（同名行程只保留一条），最新放前面，最多 20 条。
            // F64/B2：history_trips 被 LLM 压缩后不再是 JSON 数组；追加时保留摘要并置顶新行程。
            List<String> trips = parseHistoryTrips(profile.getHistoryTrips());
            trips.removeIf(t -> title.equals(t));
            trips.add(0, title);  // 最新的放前面
            if (trips.size() > 20) trips = new ArrayList<>(trips.subList(0, 20));
            profile.setHistoryTrips(JsonUtils.toJson(trips));

            // 更新行程计数
            profile.setTotalTrips(profile.getTotalTrips() + 1);

            // F53：显式刷新 updated_at。
            profile.setUpdatedAt(LocalDateTime.now());
            profileMapper.updateById(profile);
            log.info("画像自动更新: userId={}, destination={}, totalTrips={}",
                    userId, destination, profile.getTotalTrips());

            // F64/B2：历史行程条目超阈值时异步 LLM 压缩（控体积）。
            if (trips.size() > HISTORY_COMPACT_THRESHOLD) {
                // F75/B3-5：压缩纳入统一后台 LLM 治理，超限降级跳过（不影响画像更新）
                llmGovernor.runBackground("profile-compact", () -> compactHistory(userId));
            }
        } catch (Exception e) {
            log.warn("画像自动更新失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
        }
    }

    /**
     * F64/B2：解析 history_trips。压缩摘要（非 JSON 数组）时作为单个尾部条目保留，
     * 保证压缩后仍可继续追加新行程，不会因 JSON 解析失败导致整轮更新被吞掉。
     */
    private static List<String> parseHistoryTrips(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return new ArrayList<>();
        }
        if (raw.trim().startsWith("[")) {
            try {
                return new ArrayList<>(JsonUtils.parseList(raw, String.class));
            } catch (Exception ignored) {
                // 非合法 JSON 数组：按普通摘要文本处理
            }
        }
        List<String> list = new ArrayList<>();
        list.add(raw.trim());
        return list;
    }

    /**
     * F70：null / 空串 / 字面量 "null"（不区分大小写）视为"未提及"。
     */
    private static boolean isPresent(String s) {
        return s != null && !s.isBlank() && !"null".equalsIgnoreCase(s.trim());
    }

    /**
     * F70：合并两个 JSON 字符串列表（去重、保留原顺序、新值追加在后）。
     * 任一侧非合法 JSON 时按单个元素处理，保证不抛异常。
     */
    private static String mergeJsonList(String existingJson, String newJson, int cap) {
        List<String> merged = new ArrayList<>();
        if (isPresent(existingJson)) {
            try {
                merged.addAll(JsonUtils.parseList(existingJson, String.class));
            } catch (Exception ignored) {
                merged.add(existingJson.trim());
            }
        }
        if (isPresent(newJson)) {
            try {
                for (String item : JsonUtils.parseList(newJson, String.class)) {
                    if (!merged.contains(item)) {
                        merged.add(item);
                    }
                }
            } catch (Exception ignored) {
                String t = newJson.trim();
                if (!merged.contains(t)) {
                    merged.add(t);
                }
            }
        }
        if (merged.size() > cap) {
            merged = new ArrayList<>(merged.subList(merged.size() - cap, merged.size()));
        }
        return JsonUtils.toJson(merged);
    }

    /** F70：travel_style 仅接受三种合法枚举值 */
    private static boolean isValidTravelStyle(String s) {
        return "ECONOMY".equals(s) || "COMFORT".equals(s) || "LUXURY".equals(s);
    }

    /**
     * F64/B2：把 history_trips 压缩为简洁摘要（如"北京3日游×2、上海5日游×1"）。
     * 异步执行，失败不影响主流程。
     */
    private void compactHistory(Long userId) {
        // F69/B3-3：画像写入口 3（异步压缩）按 userId 串行化
        synchronized (lockFor(userId)) {
        try {
            TravelProfile p = getByUserId(userId);
            String trips = p.getHistoryTrips();
            if (trips == null || trips.isBlank()
                    || (trips.startsWith("[") && trips.length() <= HISTORY_COMPACT_MAX_CHARS)) {
                return;
            }
            String prompt = promptTemplates.profileHistoryCompact()
                    .formatted(HISTORY_COMPACT_MAX_CHARS, trips);
            String summary = chatModel.call(prompt);
            if (summary == null || summary.isBlank()) {
                return;
            }
            p.setHistoryTrips(summary.trim());
            p.setUpdatedAt(LocalDateTime.now());
            profileMapper.updateById(p);
            log.info("画像历史行程已压缩: userId={}, 长度 {} -> {}",
                    userId, trips.length(), summary.trim().length());
        } catch (Exception e) {
            log.warn("画像历史行程压缩失败（不影响主流程）: userId={}, error={}", userId, e.getMessage());
        }
        }
    }

    /**
     * 出行人员 → 出行风格映射（F53；未识别默认 COMFORT）
     */
    private String mapTravelStyle(String party) {
        if (party == null) {
            return "COMFORT";
        }
        return switch (party) {
            case "独行" -> "ECONOMY";
            case "情侣", "家庭", "朋友" -> "COMFORT";
            default -> "COMFORT";
        };
    }

    /**
     * F69/B3-3：userId → 条带锁（floorMod 保证非负下标；不同用户可能共享条带，仅轻微争用）。
     */
    private Object lockFor(Long userId) {
        long id = userId == null ? 0L : userId;
        return profileLocks[Math.floorMod(id, PROFILE_LOCK_STRIPES)];
    }
}
