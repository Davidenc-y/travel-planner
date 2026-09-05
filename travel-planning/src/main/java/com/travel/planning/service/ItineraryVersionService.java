package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.entity.Itinerary;
import com.travel.common.entity.ItineraryVersion;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.exception.BusinessException;
import com.travel.common.util.JsonUtils;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.repository.ItineraryVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M11-1：行程版本管理与 diff 快照。
 *
 * <p>终态持久化成功后自动记录：无历史快照→v1；内容变化→按景点四列表
 * （保留/调整/新增/删除）生成 diff 并递增版本。旧版本以 t_itinerary_version
 * 快照只读保留。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryVersionService {

    private final ItineraryMapper itineraryMapper;
    private final ItineraryVersionMapper versionMapper;

    /** M15-4：旧快照缺失 mindmap 时切换前确定性补齐（不修改只读快照） */
    private MindmapGenerator mindmapGenerator;

    @Autowired(required = false)
    void setMindmapGenerator(MindmapGenerator mindmapGenerator) {
        this.mindmapGenerator = mindmapGenerator;
    }

    /** 终态内容落库后调用（幂等：同内容不重复建版本）。 */
    public void recordFinalized(Long itineraryId, String content,
                                String mindmapData, BigDecimal estimatedCost) {
        if (itineraryId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            Itinerary current = itineraryMapper.selectById(itineraryId);
            if (current == null) {
                return;
            }
            ItineraryVersion latest = latest(itineraryId);
            if (latest != null && sameContent(latest.getContent(), content)) {
                return; // 内容未变化：不递增版本
            }
            int version = latest == null ? 1 : latest.getVersion() + 1;
            String diff = latest == null ? null : buildDiff(latest.getContent(), content);
            ItineraryVersion snap = new ItineraryVersion();
            snap.setItineraryId(itineraryId);
            snap.setVersion(version);
            snap.setContent(content);
            snap.setMindmapData(mindmapData);
            snap.setEstimatedCost(estimatedCost);
            snap.setVersionDiff(diff);
            snap.setCreatedAt(LocalDateTime.now());
            versionMapper.insert(snap);
            Itinerary patch = new Itinerary();
            patch.setId(itineraryId);
            patch.setVersion(version);
            patch.setVersionDiff(diff);
            itineraryMapper.updateById(patch);
            log.info("[ItineraryVersion] 快照已记录: itineraryId={}, version={}, diff={}",
                    itineraryId, version, diff == null ? "null" : diff);
        } catch (DuplicateKeyException dke) {
            log.warn("[ItineraryVersion] 版本并发冲突（已存在），跳过: itineraryId={}",
                    itineraryId);
        } catch (Exception e) {
            log.warn("[ItineraryVersion] 版本记录失败（不影响主流程）: itineraryId={}, err={}",
                    itineraryId, e.getMessage());
        }
    }

    /** 版本列表（仅本人行程，按版本倒序）。 */
    public List<Map<String, Object>> list(Long itineraryId, Long userId) {
        requireOwner(itineraryId, userId);
        return versionMapper.selectList(new QueryWrapper<ItineraryVersion>()
                        .eq("itinerary_id", itineraryId)
                        .orderByDesc("version"))
                .stream()
                .map(ItineraryVersionService::summary)
                .toList();
    }

    /** 单版详情（快照原始数据，只读回看）。 */
    public Map<String, Object> detail(Long itineraryId, int version, Long userId) {
        requireOwner(itineraryId, userId);
        ItineraryVersion snap = versionMapper.selectOne(new QueryWrapper<ItineraryVersion>()
                .eq("itinerary_id", itineraryId)
                .eq("version", version)
                .last("LIMIT 1"));
        if (snap == null) {
            throw new BusinessException(40402, "行程版本不存在: " + version);
        }
        Map<String, Object> m = summary(snap);
        m.put("content", snap.getContent());
        m.put("mindmapData", snap.getMindmapData());
        m.put("estimatedCost", snap.getEstimatedCost());
        m.put("versionDiff", snap.getVersionDiff());
        return m;
    }

    /**
     * M13-2e/M15-4：版本切换——把指定历史版本内容设为“当前使用版本”。
     * 不再递增版本号：版本总数固定，选中哪个版本即当前使用哪个版本。
     *
     * @return 当前启用的版本号（= targetVersion）
     */
    @Transactional
    public Integer switchTo(Long userId, Long itineraryId, Integer targetVersion) {
        requireOwner(itineraryId, userId);
        if (targetVersion == null || targetVersion <= 0) {
            throw new BusinessException(40402, "行程版本不存在: " + targetVersion);
        }
        ItineraryVersion target = versionMapper.selectOne(new QueryWrapper<ItineraryVersion>()
                .eq("itinerary_id", itineraryId)
                .eq("version", targetVersion)
                .last("LIMIT 1"));
        if (target == null) {
            throw new BusinessException(40402, "行程版本不存在: " + targetVersion);
        }
        Itinerary current = itineraryMapper.selectById(itineraryId);
        String mindmap = ensureMindmap(current, target);
        Itinerary patch = new Itinerary();
        patch.setId(itineraryId);
        patch.setContent(target.getContent());
        patch.setMindmapData(mindmap);
        patch.setEstimatedCost(target.getEstimatedCost());
        patch.setVersion(target.getVersion());
        patch.setVersionDiff(target.getVersionDiff());
        int rows = itineraryMapper.updateById(patch);
        if (rows <= 0) {
            throw new BusinessException(50001, "行程内容更新失败: " + itineraryId);
        }
        Itinerary updated = itineraryMapper.selectById(itineraryId);
        log.info("[ItineraryVersion] 版本切换完成（不新增版本）: itineraryId={}, activeVersion={}",
                itineraryId, targetVersion);
        return updated == null ? null : updated.getVersion();
    }

    /** M13-2e 兼容别名：历史“恢复此版本”行为等同 switchTo（不新增版本）。 */
    public Integer rollbackTo(Long userId, Long itineraryId, Integer targetVersion) {
        return switchTo(userId, itineraryId, targetVersion);
    }

    private String ensureMindmap(Itinerary current, ItineraryVersion target) {
        if (target.getMindmapData() != null && !target.getMindmapData().isBlank()) {
            return target.getMindmapData();
        }
        if (mindmapGenerator == null || current == null
                || target.getContent() == null || target.getContent().isBlank()) {
            return null;
        }
        try {
            ItineraryResponseDTO.MindmapData md = mindmapGenerator.generate(
                    current.getTitle() == null || current.getTitle().isBlank()
                            ? current.getDestination() + current.getDays() + "日游"
                            : current.getTitle(),
                    current.getDestination(),
                    current.getDays(),
                    current.getBudget() != null ? current.getBudget().toPlainString() : null,
                    target.getContent());
            return md == null ? null : JsonUtils.toJson(md);
        } catch (Exception e) {
            log.warn("[ItineraryVersion] 切换前思维导图生成失败（保留 null）: itineraryId={}, version={}",
                    current.getId(), target.getVersion());
            return null;
        }
    }

    private void requireOwner(Long itineraryId, Long userId) {
        Itinerary current = itineraryMapper.selectById(itineraryId);
        if (current == null) {
            throw new BusinessException(40401, "行程不存在: " + itineraryId);
        }
        if (!userId.equals(current.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
    }

    private ItineraryVersion latest(Long itineraryId) {
        return versionMapper.selectOne(new QueryWrapper<ItineraryVersion>()
                .eq("itinerary_id", itineraryId)
                .orderByDesc("version")
                .last("LIMIT 1"));
    }

    private static boolean sameContent(String a, String b) {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    /** 按景点名称四列表生成 diff JSON。 */
    private static String buildDiff(String oldContent, String newContent) {
        Map<String, String> oldByName = attractionEntries(oldContent);
        Map<String, String> newByName = attractionEntries(newContent);
        List<String> kept = new ArrayList<>();
        List<String> adjusted = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String name : newByName.keySet()) {
            if (oldByName.containsKey(name)) {
                if (sameContent(oldByName.get(name), newByName.get(name))) {
                    kept.add(name);
                } else {
                    adjusted.add(name);
                }
            } else {
                added.add(name);
            }
        }
        for (String name : oldByName.keySet()) {
            if (!newByName.containsKey(name)) {
                removed.add(name);
            }
        }
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("kept", kept);
        diff.put("adjusted", adjusted);
        diff.put("added", added);
        diff.put("removed", removed);
        return JsonUtils.toJson(diff);
    }

    /** content JSON routePlan.days[].attractions[] → 景点条目（name→JSON 片段）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> attractionEntries(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> root = JsonUtils.fromJson(content, Map.class);
            Object routePlan = root == null ? null : root.get("routePlan");
            if (!(routePlan instanceof Map<?, ?> route)) {
                return out;
            }
            Object days = route.get("days");
            if (!(days instanceof List<?> dayList)) {
                return out;
            }
            for (Object d : dayList) {
                if (!(d instanceof Map<?, ?> day)) {
                    continue;
                }
                Object attrs = day.get("attractions");
                if (!(attrs instanceof List<?> attrList)) {
                    continue;
                }
                for (Object a : attrList) {
                    if (a instanceof Map<?, ?> attr && attr.get("name") != null) {
                        String name = String.valueOf(attr.get("name")).trim();
                        if (!name.isEmpty()) {
                            out.put(name, JsonUtils.toJson(attr));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败按无景点处理
        }
        return out;
    }

    private static Map<String, Object> summary(ItineraryVersion v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", v.getVersion());
        m.put("createdAt", v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
        m.put("versionDiff", v.getVersionDiff());
        return m;
    }
}
