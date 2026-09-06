package com.travel.planning.memory.longterm;

import com.travel.planning.prompt.Markers;
import com.travel.common.entity.TravelProfile;
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

    private static final String PREFIX = Markers.USER_PROFILE;

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
     * M17-1：视图路径（结构化画像）。
     */
    public String assembleView(com.travel.common.dto.ProfileView view) {
        return assembleView(view, memoryProps.getProfileMaxTokens());
    }

    /**
     * M17-3：实体路径 + 行为特征段（section 为 null 时与单参路径逐字节等价）。
     */
    public String assemble(TravelProfile profile, String behaviorSection) {
        return assembleView(com.travel.common.dto.ProfileView.from(profile), behaviorSection);
    }

    /**
     * M17-3：视图路径 + 行为特征段（section 为 null 时与无段路径逐字节等价；
     * 行为段作为最后一个 part 追加，预算超限时天然最先被截断）。
     */
    public String assembleView(com.travel.common.dto.ProfileView view, String behaviorSection) {
        return assembleView(view, behaviorSection, memoryProps.getProfileMaxTokens());
    }

    public String assembleView(com.travel.common.dto.ProfileView view, String behaviorSection,
                               int maxTokens) {
        String base = assembleView(view, maxTokens);
        if (behaviorSection == null || behaviorSection.isBlank() || base.isEmpty()) {
            return base; // 无行为段 / 画像本身为空：与原路径完全一致
        }
        // 行为段与画像合并后重新过预算（段顺序不变，行为段最后 → 截断优先）
        String nl = String.valueOf('\n');
        List<String> parts = new ArrayList<>(java.util.Arrays.asList(base.split(nl, -1)));
        parts.add(behaviorSection);
        if (sessionMemoryPort.estimateTokens(String.join(nl, parts)) <= maxTokens) {
            return String.join(nl, parts);
        }
        // 超限：逐尾丢弃（行为段整段最先丢弃；仍超则等价于原路径截断结果）
        for (int i = parts.size() - 1; i > 0; i--) {
            parts.remove(i);
            if (sessionMemoryPort.estimateTokens(String.join(nl, parts)) <= maxTokens) {
                return String.join(nl, parts);
            }
        }
        return base; // 兜底：返回不含行为段的原路径结果
    }

    /**
     * B3-4/F72：带 token 预算组装画像上下文（M17-1 起内部统一走 ProfileView 视图路径，
     * 输出与旧实体直读路径逐字节等价——见 ProfileContextAssemblerViewEquivalenceTest）。
     *
     * <p>按重要性顺序排列（预算 → 风格 → 目的地 → 兴趣 → 历史），超限时保留前缀段、
     * 最后一段按 token 截断，避免画像段吃满注入总预算。</p>
     */
    public String assemble(TravelProfile profile, int maxTokens) {
        return assembleView(com.travel.common.dto.ProfileView.from(profile), maxTokens);
    }

    /**
     * M17-1：视图路径组装（渲染规则与旧 addJsonList 逐字对齐）。
     */
    public String assembleView(com.travel.common.dto.ProfileView view, int maxTokens) {
        if (view == null) {
            return "";
        }
        if (maxTokens <= 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(view.budgetRange())) {
            parts.add("预算区间：" + view.budgetRange());
        }
        if (StringUtils.hasText(view.travelStyle())) {
            parts.add("出行风格：" + view.travelStyle());
        }
        if (StringUtils.hasText(view.consumeLevel())) {
            parts.add("消费水平：" + view.consumeLevel());
        }
        addList(parts, "常去目的地", view.destinations());
        addList(parts, "偏好兴趣", view.interests());
        // 历史：摘要通道优先（压缩后形态），否则标题级事实
        if (StringUtils.hasText(view.historySummary())) {
            parts.add("历史行程：" + view.historySummary());
        } else {
            List<String> titles = new ArrayList<>();
            view.history().forEach(f -> titles.add(f.title()));
            addList(parts, "历史行程", titles);
        }
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

    /** M17-1：视图列表渲染（与旧 addJsonList 输出逐字一致：顿号连接） */
    private void addList(List<String> parts, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        parts.add(label + "：" + String.join("、", items));
    }
}
