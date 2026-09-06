package com.travel.common.dto;

import com.travel.common.entity.TravelProfile;
import com.travel.common.util.JsonUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * M17-1：画像统一视图（Phase A 结构化）。
 *
 * <p>消除弱 schema（实体 JSON 字符串列的手工解析）与 history_trips 的
 * 「JSON 数组 / LLM 压缩文本」双形态猜测：{@link #from(TravelProfile)} 是
 * 唯一转换点——JSON 数组 → {@code history}（标题级事实）；非 JSON 文本 →
 * {@code historySummary}（压缩摘要通道）；非法 JSON 降级按原文单元素处理
 * （与既有 addJsonList 兜底语义一致，保证组装输出逐字节等价）。</p>
 *
 * @param userId         用户 ID
 * @param destinations   常去目的地（解析后列表）
 * @param interests      偏好兴趣（解析后列表）
 * @param budgetRange    预算区间原文
 * @param travelStyle    出行风格
 * @param consumeLevel   消费水平
 * @param history        结构化历史行程（标题级，最新在前；与 historySummary 互补）
 * @param historySummary LLM 压缩摘要（history_trips 为纯文本时的通道；新列
 *                       history_summary 落库后同步携带）
 * @param totalTrips     累计行程数
 */
public record ProfileView(
        Long userId,
        List<String> destinations,
        List<String> interests,
        String budgetRange,
        String travelStyle,
        String consumeLevel,
        List<ProfileTripFact> history,
        String historySummary,
        Integer totalTrips) {

    /** 实体 → 视图（双形态容错单点）。 */
    public static ProfileView from(TravelProfile p) {
        if (p == null) {
            return new ProfileView(null, List.of(), List.of(), null, null, null,
                    List.of(), null, 0);
        }
        List<String> destinations = parseStringList(p.getPreferredDestinations());
        List<String> interests = parseStringList(p.getPreferredInterests());
        HistoryResolution h = resolveHistory(p.getHistoryTrips());
        // 门禁2评审修复1：渲染通道仅以 history_trips 的解析结果为准——history_summary
        // 新列不参与视图（过渡期"零读取行为变化"红线；该列是 M17-2 写入侧的前瞻通道，
        // 待后续读取路径切换时再启用）。避免 compact 双写后 recordTrip 写回 JSON 数组
        // 时新行程标题被陈旧摘要抢占。
        return new ProfileView(
                p.getUserId(),
                destinations,
                interests,
                p.getBudgetRange(),
                p.getTravelStyle(),
                p.getConsumeLevel(),
                h.facts(),
                h.summary(),
                p.getTotalTrips());
    }

    /** JSON 列表解析；非法 JSON 按原文单元素降级（与既有兜底一致）。 */
    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return List.of();
        }
        try {
            List<String> items = JsonUtils.parseList(json, String.class);
            if (items == null || items.isEmpty()) {
                // 与旧 addJsonList 兜底逐字对齐：合法 JSON 但空列表 → 按原文展示
                return List.of(json.trim());
            }
            // 门禁2评审修复3：null 元素过滤（List.copyOf 遇 null 抛 NPE；旧算法 join 渲染 "null"）
            List<String> cleaned = new ArrayList<>(items.size());
            for (String item : items) {
                cleaned.add(item == null ? "null" : item);
            }
            return List.copyOf(cleaned);
        } catch (Exception ignored) {
            return List.of(json.trim());
        }
    }

    /** history_trips 双形态判别：JSON 数组 → 标题事实；否则 → 摘要通道。 */
    private static HistoryResolution resolveHistory(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) {
            return new HistoryResolution(List.of(), null);
        }
        if (raw.trim().startsWith("[")) {
            try {
                List<String> items = JsonUtils.parseList(raw, String.class);
                if (items != null) {
                    List<ProfileTripFact> facts = new ArrayList<>();
                    for (String t : items) {
                        facts.add(ProfileTripFact.ofTitle(t));
                    }
                    return new HistoryResolution(List.copyOf(facts), null);
                }
            } catch (Exception ignored) {
                // 非合法 JSON 数组 → 摘要通道
            }
        }
        return new HistoryResolution(List.of(), raw.trim());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private record HistoryResolution(List<ProfileTripFact> facts, String summary) {
    }
}
