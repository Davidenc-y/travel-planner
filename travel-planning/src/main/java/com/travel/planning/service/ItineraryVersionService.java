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
    /** M28-6：版本切换约束列重算（routePlan 解析） */
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** M15-4：旧快照缺失 mindmap 时切换前确定性补齐（不修改只读快照） */
    private MindmapGenerator mindmapGenerator;

    @Autowired(required = false)
    void setMindmapGenerator(MindmapGenerator mindmapGenerator) {
        this.mindmapGenerator = mindmapGenerator;
    }

    /** 终态内容落库后调用（幂等：同内容不重复建版本）。约束快照回退读当前列。 */
    public void recordFinalized(Long itineraryId, String content,
                                String mindmapData, BigDecimal estimatedCost) {
        recordFinalized(itineraryId, content, mindmapData, estimatedCost, null, null, null);
    }

    /**
     * M28-7：带约束快照的版本记录——REFINE 场景必须传显式约束值：
     * 版本记录发生在 applyRefinedConstraints 之前，回退读列会错记旧约束
     * （"预算9000"的 v2 快照若读列会记成 5000）。
     */
    public void recordFinalized(Long itineraryId, String content,
                                String mindmapData, BigDecimal estimatedCost,
                                Integer days, BigDecimal budget, String startDate) {
        if (itineraryId == null || content == null || content.isBlank()) {
            return;
        }
        try {
            Itinerary current = itineraryMapper.selectById(itineraryId);
            if (current == null) {
                return;
            }
            if (days == null) {
                days = current.getDays();
            }
            if (budget == null) {
                budget = current.getBudget();
            }
            if (startDate == null) {
                startDate = current.getStartDate();
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
            snap.setDays(days);
            snap.setBudget(budget);
            snap.setStartDate(startDate);
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
        // M28-7：切换后恢复约束列——优先版本快照（budget 唯一可恢复来源）；
        // 存量快照 NULL 时 fallback：days 用 content 推断，budget/startDate 保持现值
        applySwitchedConstraints(itineraryId, current, target);
        Itinerary updated = itineraryMapper.selectById(itineraryId);
        log.info("[ItineraryVersion] 版本切换完成（不新增版本）: itineraryId={}, activeVersion={}",
                itineraryId, targetVersion);
        return updated == null ? null : updated.getVersion();
    }

    /**
     * M28-7：版本切换后恢复约束列。
     *
     * <p>优先 target 快照（days/budget/start_date；M28-7 起新版本记录时写入，
     * budget 唯一可恢复来源）；快照 NULL（存量版本行）fallback：days 从 content
     * 确定性推断（天数组长度）、start_date 取首日，budget 无来源保持现值。
     * 修 historic 缺陷：切回 v1 后 t_itinerary.budget 仍是 vN 的最新值
     * （“最新预算覆盖旧预算”），偏好标签/详情页/分享页全部跟着错。</p>
     */
    private void applySwitchedConstraints(Long itineraryId, Itinerary current, ItineraryVersion target) {
        try {
            Integer newDays = target.getDays();
            java.math.BigDecimal newBudget = target.getBudget();
            String newStart = target.getStartDate();
            String source = "snapshot";
            if (newDays == null && newBudget == null && newStart == null) {
                source = "content-fallback";
                com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(target.getContent());
                com.fasterxml.jackson.databind.JsonNode days = root.path("days");
                if (!days.isArray() || days.isEmpty()) {
                    days = root.path("routePlan").path("days");
                }
                if (days.isArray() && !days.isEmpty()) {
                    newDays = days.size();
                    String date = days.get(0).path("date").asText(null);
                    if (date != null && date.length() >= 10) {
                        newStart = java.time.LocalDate.parse(date.trim().substring(0, 10)).toString();
                    }
                }
            }
            boolean daysChanged = newDays != null && !newDays.equals(current.getDays());
            boolean budgetChanged = newBudget != null
                    && (current.getBudget() == null || newBudget.compareTo(current.getBudget()) != 0);
            boolean startChanged = newStart != null && !newStart.equals(current.getStartDate());
            if (!daysChanged && !budgetChanged && !startChanged) {
                return;
            }
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary>()
                            .eq("id", itineraryId);
            if (daysChanged) {
                uw.set("days", newDays);
            }
            if (budgetChanged) {
                uw.set("budget", newBudget);
            }
            if (startChanged) {
                uw.set("start_date", newStart);
            }
            itineraryMapper.update(null, uw);
            log.info("[ItineraryVersion] 版本切换约束列已恢复({}): itineraryId={}, days={}, budget={}, startDate={}",
                    source, itineraryId,
                    daysChanged ? newDays : "(unchanged)",
                    budgetChanged ? newBudget.toPlainString() : "(unchanged)",
                    startChanged ? newStart : "(unchanged)");
        } catch (Exception e) {
            log.debug("[ItineraryVersion] 版本切换约束列恢复降级: itineraryId={}, {}", itineraryId, e.getMessage());
        }
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
            // M28-7：days/budget 优先 target 快照——current 列此时仍是切换前的最新值
            // （如 v2 的 9000），直接用会把旧版本补生成的思维导图预算写成最新值
            Integer days = target.getDays() != null ? target.getDays() : current.getDays();
            java.math.BigDecimal budget = target.getBudget() != null
                    ? target.getBudget() : current.getBudget();
            ItineraryResponseDTO.MindmapData md = mindmapGenerator.generate(
                    current.getTitle() == null || current.getTitle().isBlank()
                            ? current.getDestination() + days + "日游"
                            : current.getTitle(),
                    current.getDestination(),
                    days,
                    budget != null ? budget.toPlainString() : null,
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
