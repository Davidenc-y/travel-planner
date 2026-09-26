package com.travel.memory.longterm;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AB-5b：用户画像结构化端口（slot/model_usage 两表读写）。
 *
 * <p>中立端口范式（同 {@code TripFactsPort}/{@code ItineraryVersionPort}/{@code KnowledgePort}）：
 * 端口在 travel-memory 定义、planning 侧经 Mapper 实现（ProfileSlotPortImpl）注入；
 * chat-domain（AB-5c ChatService 采集）与 planning（AB-5d BehaviorProfileService 聚合）
 * 经端口取数，不引入模块反向依赖。方法面仅基础类型与 record，禁引用具体模块实体。</p>
 *
 * <p><b>E-33 语义</b>：{@code travel.profile.slot-enabled} 默认 false=全部采集单元不调用
 * 本端口（关闭态零 DB 写）；两表零存量消费=端口装配零行为。</p>
 */
public interface ProfileSlotPort {

    /**
     * 时段使用计数 upsert：同 (user_id, slot_id) 行 use_count+1，行不存在则插入。
     * AB-5c：ChatService sendMessage/prepareStream 入口按 hour/4 计算 slotId。
     */
    void upsertSlotUsage(Long userId, int slotId);

    /**
     * 模型使用计数 upsert：同 (user_id, model_key, slot_id) 行 use_count+1、
     * total_tokens 累加、avg_ttft_ms 滚动均值。AB-5c：模型路由落定后调用。
     *
     * @param ttftMs 首 token 延迟毫秒（null=本轮不计入均值）
     */
    void incrementModelUsage(Long userId, String modelKey, int slotId,
                             long tokens, Integer ttftMs);

    /**
     * 查询用户全部时段画像行（6 段零填充由查询方处理；无行=空列表）。
     */
    List<SlotUsage> querySlots(Long userId);

    /**
     * 查询用户模型使用记录（含 slot_id=-1 汇总行；聚合在查询方，AB-5d）。
     */
    List<ModelUsage> queryModelUsage(Long userId);

    /** 单时段画像（t_user_profile_slot 行的最小字段集） */
    record SlotUsage(
            int slotId,
            int useCount,
            /** 高频景点标签聚合 JSON（原样透传） */
            String preferredTags,
            /** 常用模型聚合 JSON（原样透传） */
            String preferredModels,
            LocalDateTime updatedAt) {
    }

    /** 单模型使用记录（t_user_model_usage 行的最小字段集） */
    record ModelUsage(
            String modelKey,
            int slotId,
            int useCount,
            long totalTokens,
            Integer avgTtftMs,
            LocalDateTime updatedAt) {
    }
}
