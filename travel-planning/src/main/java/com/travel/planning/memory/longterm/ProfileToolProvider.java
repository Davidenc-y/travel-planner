package com.travel.planning.memory.longterm;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.travel.common.util.JsonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户画像 Tool 提供者（F64/B2）
 *
 * <p>把长期画像暴露为 {@code get_user_profile} / {@code save_user_profile} 两个
 * FunctionToolCallback，供 preference / attraction_filter 等 Agent 主动读写画像。
 * userId 不依赖 LLM 传入，而是从工具调用上下文（ToolContext → RunnableConfig.metadata）注入，
 * 避免 LLM 编造 userId 造成脏数据（F52 教训）。</p>
 *
 * <p>框架依据：Spring AI Alibaba graph-core/agent-framework 的
 * {@code ToolContextHelper.getConfig(toolContext)} 可拿到当前执行的 {@link RunnableConfig}，
 * 其 metadata 由上层 ChatService（聊天链）与 ItineraryService（行程链）写入 userId。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileToolProvider {

    /** RunnableConfig.metadata 中的 userId key（聊天链 ChatService / 行程链 ItineraryService 写入） */
    public static final String USER_ID_METADATA_KEY = "userId";

    private final ProfilePort profilePort;

    /**
     * 注册两个画像工具：
     * <ul>
     *   <li>get_user_profile：读取当前用户画像（不存在则创建空画像），返回 JSON 字符串；</li>
     *   <li>save_user_profile：按 LLM 确认的新偏好更新画像，未提及字段保持 null 不覆盖。</li>
     * </ul>
     */
    public List<ToolCallback> toolCallbacks() {
        ToolCallback getTool = FunctionToolCallback.builder(
                        "get_user_profile",
                        (GetUserProfileRequest req, ToolContext ctx) -> getProfile(ctx))
                .description("获取当前登录用户的旅行画像（常去目的地、偏好兴趣、预算区间、出行风格、历史行程摘要）。"
                        + "userId 由系统上下文自动注入，无需也不能在参数中提供。返回 JSON 字符串。")
                .inputType(GetUserProfileRequest.class)
                .build();

        ToolCallback saveTool = FunctionToolCallback.builder(
                        "save_user_profile",
                        (SaveUserProfileRequest req, ToolContext ctx) -> saveProfile(req, ctx))
                .description("更新当前登录用户的旅行画像偏好。仅当用户明确表达了新的偏好"
                        + "（目的地/兴趣/预算区间/出行风格）时才调用；未提及的字段请保持 null，"
                        + "系统不会覆盖原有值。userId 由系统上下文自动注入，无需在参数中提供。返回 JSON 字符串。")
                .inputType(SaveUserProfileRequest.class)
                .build();

        return List.of(getTool, saveTool);
    }

    private String getProfile(ToolContext ctx) {
        Long userId = resolveUserId(ctx);
        if (userId == null) {
            log.warn("[ProfileTool] get_user_profile 失败：无法从上下文解析 userId");
            return "{\"error\":\"userId 缺失，无法读取用户画像\"}";
        }
        var profile = profilePort.getOrCreate(userId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("userId", profile.getUserId());
        view.put("preferredDestinations", profile.getPreferredDestinations());
        view.put("preferredInterests", profile.getPreferredInterests());
        view.put("budgetRange", profile.getBudgetRange());
        view.put("travelStyle", profile.getTravelStyle());
        view.put("historyTrips", profile.getHistoryTrips());
        view.put("totalTrips", profile.getTotalTrips());
        log.info("[ProfileTool] get_user_profile: userId={}, totalTrips={}", userId, profile.getTotalTrips());
        return JsonUtils.toJson(view);
    }

    private String saveProfile(SaveUserProfileRequest req, ToolContext ctx) {
        Long userId = resolveUserId(ctx);
        if (userId == null) {
            log.warn("[ProfileTool] save_user_profile 失败：无法从上下文解析 userId");
            return "{\"error\":\"userId 缺失，无法保存用户画像\"}";
        }
        profilePort.update(userId,
                req.preferredDestinations() != null ? JsonUtils.toJson(req.preferredDestinations()) : null,
                req.preferredInterests() != null ? JsonUtils.toJson(req.preferredInterests()) : null,
                req.budgetRange(),
                req.travelStyle());
        log.info("[ProfileTool] save_user_profile: userId={}, budgetRange={}, travelStyle={}",
                userId, req.budgetRange(), req.travelStyle());
        return "{\"success\":true}";
    }

    /**
     * 从 ToolContext 解析 userId：
     * 1. ToolContextHelper.getConfig → RunnableConfig；
     * 2. RunnableConfig.metadata("userId")，兼容 Number 与数字字符串。
     */
    private static Long resolveUserId(ToolContext ctx) {
        try {
            Optional<RunnableConfig> config = ToolContextHelper.getConfig(ctx);
            if (config.isPresent()) {
                Optional<Object> value = config.get().metadata(USER_ID_METADATA_KEY);
                if (value.isPresent() && value.get() instanceof Number n) {
                    return n.longValue();
                }
                if (value.isPresent()) {
                    try {
                        return Long.valueOf(value.get().toString());
                    } catch (NumberFormatException ignored) {
                        log.warn("[ProfileTool] userId 元数据非数值: {}", value.get());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[ProfileTool] 解析 ToolContext 失败: {}", e.getMessage());
        }
        return null;
    }

    /** get_user_profile 入参：空对象，userId 由系统上下文注入 */
    public record GetUserProfileRequest() {
    }

    /** save_user_profile 入参：未提及字段保持 null（不覆盖原值） */
    public record SaveUserProfileRequest(List<String> preferredDestinations,
                                         List<String> preferredInterests,
                                         String budgetRange,
                                         String travelStyle) {
    }
}
