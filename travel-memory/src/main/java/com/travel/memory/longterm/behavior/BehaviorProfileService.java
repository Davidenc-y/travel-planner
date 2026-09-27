package com.travel.memory.longterm.behavior;

import com.travel.common.entity.UserBehaviorProfile;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * BehaviorProfileService 公共契约（AD-2e 接口化）。
 *
 * <p>实现见 {@link BehaviorProfileServiceImpl}（@Service 在 Impl）；公有方法签名逐字提取
 * （零语义变更）。三结果 record 与 activeHourPeakText 静态（BehaviorSections main 调用方在位）
 * 迁入本接口——{@code BehaviorProfileService.SlotBucket} 等既有 FQN 引用面零变动；
 * inferTravelStyle/inferConsumeLevel（main 外部零调用）留守实现类。</p>
 */
public interface BehaviorProfileService {

    /** AB-5d：用户时段画像（24 桶聚合）。 */
    List<SlotBucket> getSlotProfile(Long userId);

    /** AB-5d：用户模型使用摘要。 */
    List<ModelSummary> getModelUsageSummary(Long userId);

    /** 行为画像查询（无则 Optional.empty）。 */
    Optional<UserBehaviorProfile> getBehavior(Long userId);

    /** 开关控制的重算（关闭=零副作用）。 */
    void recomputeIfEnabled(Long userId);

    /** 行为画像重算（聚合→写回）。 */
    UserBehaviorProfile recompute(Long userId);

    /** 可靠性门槛（D-Q2）：行程数或会话数达门槛才可注入。 */
    boolean isReliable(UserBehaviorProfile row);

    /** AB-5d：时段桶（活跃时段画像行）。 */
    record SlotBucket(int slotId, int useCount, String topTag, String topModel) {
    }

    /** AB-5d：单模型聚合摘要（avgTtftMs=null=无有效 ttft 行）。 */
    record ModelSummary(String modelKey, long useCount, long totalTokens,
                        Double avgTtftMs) {
    }

    /** 聚合结果（持久化前形态；JSON 序列化在 apply 边界完成） */
    record Aggregated(
            int[] activeHours,
            BigDecimal planWeeklyAvg,
            BigDecimal budgetP25,
            BigDecimal budgetP50,
            BigDecimal budgetP75,
            List<Map<String, Object>> destFreq,
            String partyMode,
            Integer partyConfidence,
            BigDecimal refineAvg,
            int tripCount,
            long sessionCount) {
    }

    /** 活跃峰值时段文本（3 小时滑动窗最大计数），如 "20-23点"；无数据 null。 */
    static String activeHourPeakText(int[] hours) {
        if (hours == null || hours.length != 24) {
            return null;
        }
        int total = Arrays.stream(hours).sum();
        if (total == 0) {
            return null;
        }
        int best = 0;
        int bestSum = -1;
        for (int s = 0; s + 3 <= 24; s++) {
            int sum = hours[s] + hours[s + 1] + hours[s + 2];
            if (sum > bestSum) {
                bestSum = sum;
                best = s;
            }
        }
        if (bestSum <= 0) {
            return null;
        }
        return best + "-" + (best + 3) + "点";
    }
}
