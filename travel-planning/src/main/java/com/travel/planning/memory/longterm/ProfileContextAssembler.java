package com.travel.planning.memory.longterm;

import com.travel.common.entity.TravelProfile;
import com.travel.common.util.JsonUtils;
import com.travel.planning.memory.shortterm.SessionMemoryPort;
import com.travel.planning.memory.shortterm.ShortTermMemoryProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户画像 → 上下文文本组装（F50/Phase A）。
 *
 * <p>把画像字段组装为 {@code 【用户画像】…} 前缀，注入聊天链与行程链的输入，
 * 供 preference 阶段消费；画像为空时返回空串（不注入）。</p>
 */
@Component
public class ProfileContextAssembler {

    private static final String PREFIX = "【用户画像】";

    private final SessionMemoryPort sessionMemoryPort;
    private final ShortTermMemoryProperties memoryProps;

    public ProfileContextAssembler(SessionMemoryPort sessionMemoryPort, ShortTermMemoryProperties memoryProps) {
        this.sessionMemoryPort = sessionMemoryPort;
        this.memoryProps = memoryProps;
    }

    /**
     * 组装画像上下文（默认 profile-max-tokens 预算）；profile 为 null 或全空时返回空串
     */
    public String assemble(TravelProfile profile) {
        return assemble(profile, memoryProps.getProfileMaxTokens());
    }

    /**
     * B3-4/F72：带 token 预算组装画像上下文。
     *
     * <p>按重要性顺序排列（预算 → 风格 → 目的地 → 兴趣 → 历史），超限时保留前缀段、
     * 最后一段按 token 截断，避免画像段吃满注入总预算。</p>
     */
    public String assemble(TravelProfile profile, int maxTokens) {
        if (profile == null) {
            return "";
        }
        if (maxTokens <= 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(profile.getBudgetRange())) {
            parts.add("预算区间：" + profile.getBudgetRange());
        }
        if (StringUtils.hasText(profile.getTravelStyle())) {
            parts.add("出行风格：" + profile.getTravelStyle());
        }
        addJsonList(parts, "常去目的地", profile.getPreferredDestinations());
        addJsonList(parts, "偏好兴趣", profile.getPreferredInterests());
        addJsonList(parts, "历史行程", profile.getHistoryTrips());
        if (parts.isEmpty()) {
            return "";
        }
        if (sessionMemoryPort.estimateTokens(PREFIX + "\n" + String.join("\n", parts)) <= maxTokens) {
            return PREFIX + "\n" + String.join("\n", parts);
        }
        // 超限：按重要性顺序逐段保留，最后一段按 token 截断
        List<String> kept = new ArrayList<>();
        int used = sessionMemoryPort.estimateTokens(PREFIX);
        for (String part : parts) {
            int t = sessionMemoryPort.estimateTokens(part);
            if (used + t > maxTokens) {
                kept.add(truncatePart(part, maxTokens - used));
                break;
            }
            kept.add(part);
            used += t;
        }
        return PREFIX + "\n" + String.join("\n", kept);
    }

    /** 按 token 预算截断单段文本（中文≈1 token/字，其他≈0.25/字），追加"已裁剪"标记 */
    private String truncatePart(String text, int maxTokens) {
        if (maxTokens <= 8) {
            return "";
        }
        int budget = maxTokens - 4;
        double cost = 0;
        int idx = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            cost += Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN ? 1.0 : 0.25;
            if (cost > budget) {
                break;
            }
            idx = i + 1;
        }
        if (idx >= text.length()) {
            return text;
        }
        return text.substring(0, Math.max(idx, 8)) + "…（已裁剪）";
    }

    private void addJsonList(List<String> parts, String label, String json) {
        if (json == null || json.isBlank() || "[]".equals(json.trim())) {
            return;
        }
        try {
            List<String> items = JsonUtils.parseList(json, String.class);
            if (items != null && !items.isEmpty()) {
                parts.add(label + "：" + String.join("、", items));
                return;
            }
        } catch (Exception ignored) {
            // F64/B2：history_trips 压缩后可能不再是 JSON 数组，按原文展示。
        }
        parts.add(label + "：" + json.trim());
    }
}
