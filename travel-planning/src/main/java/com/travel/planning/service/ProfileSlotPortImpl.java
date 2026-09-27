package com.travel.planning.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.entity.ProfileSlot;
import com.travel.common.entity.UserModelUsage;
import com.travel.common.util.JsonUtils;
import com.travel.memory.longterm.ProfileSlotPort;
import com.travel.planning.repository.ProfileSlotMapper;
import com.travel.planning.repository.UserModelUsageMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Override
    public void enrichSlotTags(Long userId, int slotId, List<String> tags) {
        enrichSlot(userId, slotId, tags, null);
    }

    @Override
    public void enrichSlotModels(Long userId, int slotId, List<String> models) {
        enrichSlot(userId, slotId, null, models);
    }

    /**
     * AC-1c（L16）：聚合公共面——读行→既有 JSON 解析（malformed 按空起点，fail-open）→
     * 并入新键计数+1→cnt 降序（同频按键名字典序，确定性）→保留 top5→两列成对回写
     * （未涉及侧透传原值）。零有效新增键=跳过 UPDATE（S-E5 同值免写先例）；
     * 行不存在先建零计数行（ensureRow，不污染 use_count 语义）。
     */
    private void enrichSlot(Long userId, int slotId, List<String> tags, List<String> models) {
        if (userId == null) {
            return;
        }
        List<String> additions = tags != null ? tags : models;
        if (additions == null || additions.stream().allMatch(a -> a == null || a.isBlank())) {
            return;
        }
        String keyField = tags != null ? "tag" : "model";
        slotMapper.ensureRow(userId, slotId);
        ProfileSlot row = slotMapper.selectByUser(userId).stream()
                .filter(r -> r.getSlotId() != null && r.getSlotId() == slotId)
                .findFirst().orElse(null);
        if (row == null) {
            return;
        }
        String mergedTags = tags != null
                ? mergeTopN(row.getPreferredTags(), tags, "tag")
                : row.getPreferredTags();
        String mergedModels = models != null
                ? mergeTopN(row.getPreferredModels(), models, "model")
                : row.getPreferredModels();
        if (mergedTags == null && mergedModels == null) {
            return;
        }
        slotMapper.updateAggregates(userId, slotId, mergedTags, mergedModels);
    }

    /** 既有 [{key:val,cnt:n},...] 与新增键合并计数，cnt 降序取 top5 重排；零有效新增返回 null。 */
    private String mergeTopN(String existingJson, List<String> additions, String keyField) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                for (JsonNode item : JsonUtils.getMapper().readTree(existingJson)) {
                    JsonNode key = item.get(keyField);
                    JsonNode cnt = item.get("cnt");
                    if (key != null && !key.isNull() && cnt != null && !cnt.isNull()) {
                        counts.put(key.asText(), cnt.asInt());
                    }
                }
            } catch (Exception e) {
                // malformed 既有 JSON：按空起点重建（fail-open，不抛出）
            }
        }
        boolean added = false;
        for (String a : additions) {
            if (a == null || a.isBlank()) {
                continue;
            }
            counts.merge(a.trim(), 1, Integer::sum);
            added = true;
        }
        if (!added) {
            return null;
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((x, y) -> {
            int c = Integer.compare(y.getValue(), x.getValue());
            return c != 0 ? c : x.getKey().compareTo(y.getKey());
        });
        List<Map<String, Object>> top = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries.subList(0, Math.min(5, entries.size()))) {
            top.add(Map.of(keyField, e.getKey(), "cnt", e.getValue()));
        }
        return JsonUtils.toJson(top);
    }
}
