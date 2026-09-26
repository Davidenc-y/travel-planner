package com.travel.planning.service;

import com.travel.common.entity.ProfileSlot;
import com.travel.common.entity.UserModelUsage;
import com.travel.memory.longterm.ProfileSlotPort;
import com.travel.planning.repository.ProfileSlotMapper;
import com.travel.planning.repository.UserModelUsageMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AB-5b：用户画像结构化端口实现（planning 侧，{@code TripFactsPortImpl} 同款中立端口范式）。
 *
 * <p>chat-domain（AB-5c ChatService 采集）与 planning（AB-5d BehaviorProfileService 聚合）
 * 经 {@link ProfileSlotPort} 取数，不引入模块反向依赖（planning 经 chat-domain 传递依赖
 * 可见 travel-memory 类型）。</p>
 *
 * <p><b>E-33</b>：{@code travel.profile.slot-enabled=false} 默认关=无人调用=装配零行为
 * （两表零存量消费；Bean 装配仅构造注入，不触碰 DB）。</p>
 */
@Component
public class ProfileSlotPortImpl implements ProfileSlotPort {

    private final ProfileSlotMapper slotMapper;
    private final UserModelUsageMapper modelUsageMapper;

    public ProfileSlotPortImpl(ProfileSlotMapper slotMapper, UserModelUsageMapper modelUsageMapper) {
        this.slotMapper = slotMapper;
        this.modelUsageMapper = modelUsageMapper;
    }

    @Override
    public void upsertSlotUsage(Long userId, int slotId) {
        slotMapper.upsertSlotUsage(userId, slotId);
    }

    @Override
    public void incrementModelUsage(Long userId, String modelKey, int slotId,
                                    long tokens, Integer ttftMs) {
        modelUsageMapper.incrementModelUsage(userId, modelKey, slotId, tokens, ttftMs);
    }

    @Override
    public List<SlotUsage> querySlots(Long userId) {
        List<ProfileSlot> rows = slotMapper.selectByUser(userId);
        return rows.stream()
                .map(r -> new SlotUsage(r.getSlotId(), r.getUseCount(),
                        r.getPreferredTags(), r.getPreferredModels(), r.getUpdatedAt()))
                .toList();
    }

    @Override
    public List<ModelUsage> queryModelUsage(Long userId) {
        List<UserModelUsage> rows = modelUsageMapper.selectByUser(userId);
        return rows.stream()
                .map(r -> new ModelUsage(r.getModelKey(), r.getSlotId(), r.getUseCount(),
                        r.getTotalTokens(), r.getAvgTtftMs(), r.getUpdatedAt()))
                .toList();
    }
}
