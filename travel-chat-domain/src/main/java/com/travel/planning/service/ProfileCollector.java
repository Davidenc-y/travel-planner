package com.travel.planning.service;

import com.travel.aigateway.route.ModelRoutingContext;
import com.travel.memory.longterm.ProfileSlotPort;
import com.travel.planning.trace.TtftChannel;
import com.travel.planning.trace.TraceContext;
import com.travel.stream.service.ChatStreamExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AC-3a：用户画像采集门面（ChatService 中 AB-5 系列增量面拆出，AA-7 StreamRouteSupport
 * 同款逐字搬运纪律——方法体/日志文案/注释语义 verbatim，仅 this.X→字段注入机械变换）。
 *
 * <p>职责：①时段使用采集（sendMessage/prepareStream 全重载汇聚处每轮一次）
 * ②模型×时段使用采集（runStream 返回侧=JSON/SSE 两路径公共完成点）。</p>
 *
 * <p><b>E-33</b>：{@code travel.profile.slot-enabled=false} 默认关=零调用零 DB 写；
 * 采集异常 fail-open 不阻断主流程（同画像更新降级口径）。</p>
 */
@Slf4j
@Component
public class ProfileCollector {

    /** AB-5c：时段画像采集开关（默认关=E-33 关闭态零调用零 DB 写；审计实弹开启）。 */
    @Value("${travel.profile.slot-enabled:false}")
    private boolean profileSlotEnabled = false;

    /** AB-5c：画像结构化端口（optional 注入；null 或开关关=零采集）。 */
    @Autowired(required = false)
    private ProfileSlotPort profileSlotPort;

    @Autowired(required = false)
    void setProfileSlotPort(ProfileSlotPort profileSlotPort) {
        this.profileSlotPort = profileSlotPort;
    }

    /** AB-5c：测试直连（同包；生产装配走 @Value 字段注入）。 */
    void setProfileSlotCollector(boolean enabled, ProfileSlotPort port) {
        this.profileSlotEnabled = enabled;
        this.profileSlotPort = port;
    }

    /**
     * AB-5c：时段使用采集——sendMessage/prepareStream 全重载汇于 prepareStream 主方法
     * （每轮有效入口恰好一次，重放/重复发送亦为该时段真实用户动作）；E-33：flag 默认关
     * =零调用零 DB 写；fail-open（采集异常不阻断主流程，同画像更新降级口径）。
     */
    void collectSlotUsage(Long userId) {
        if (profileSlotEnabled && profileSlotPort != null) {
            try {
                profileSlotPort.upsertSlotUsage(userId,
                        slotOf(LocalDateTime.now()));
            } catch (Exception e) {
                log.warn("[ProfileSlot] 时段画像采集失败（不影响主流程）: userId={}, err={}",
                        userId, e.getMessage());
            }
        }
    }

    /**
     * AB-5c：时段编号计算（hour/4 → 0~5 共 6 段，与 t_user_profile_slot.slot_id 口径一致）。
     * package-static 供测试直连（V-1 recordBlockingTtft 先例）。
     */
    static int slotOf(LocalDateTime t) {
        return t.getHour() / 4;
    }

    /**
     * AB-5c-2：模型×时段使用采集（runStream 返回侧=JSON/SSE 两路径公共完成点——
     * runStreamInternal 阻塞至图完成，SSE 仅经 listener 旁路推送）。E-33：flag 默认关=
     * 零调用零 DB 写；fail-open 不影响主流程。模型 key 取实际路由值（ModelRoutingContext
     * .routed()，本方法仍在 runWith 作用域内=ThreadLocal 未清），无路由回落请求级模型，
     * 双空则不计。ttft 经 TtftChannel.take(requestId) 尽力取值（TraceContext 未激活=null
     * 容忍，Port 侧 CASE WHEN 不触碰均值）。package 供测试直连（V-1 先例）。
     */
    void collectModelUsage(ChatStreamExecutor.ChatStreamPrepared prepared,
                           ChatStreamExecutor.ChatStreamResult result) {
        if (!profileSlotEnabled || profileSlotPort == null) {
            return;
        }
        try {
            String routed = ModelRoutingContext.routed();
            String modelKey = routed != null && !routed.isBlank() ? routed : prepared.model();
            if (modelKey == null || modelKey.isBlank()) {
                return;
            }
            Long ttftRaw = TraceContext.active()
                    ? TtftChannel.take(TraceContext.current().requestId) : null;
            Integer ttftMs = ttftRaw == null ? null : ttftRaw.intValue();
            int slotId = slotOf(LocalDateTime.now());
            profileSlotPort.incrementModelUsage(prepared.userId(), modelKey,
                    slotId, result == null ? 0L : result.aiTokens(), ttftMs);
            // AC-1c（L16）：同一模型键并入当前时段 preferred_models 聚合
            // （同 flag 守卫/同 try 吞错面，fail-open 不影响主流程）
            profileSlotPort.enrichSlotModels(prepared.userId(), slotId, List.of(modelKey));
        } catch (Exception e) {
            log.warn("[ProfileSlot] 模型使用采集失败（不影响主流程）: userId={}, err={}",
                    prepared.userId(), e.getMessage());
        }
    }
}
