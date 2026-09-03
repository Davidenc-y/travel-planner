package com.travel.planning.workflow.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.common.util.AgentOutputUtils;
import com.travel.common.util.JsonUtils;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.config.ItineraryConflictCheckProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M8-3：行程冲突确定性校验器（规则链，budget_retry 同构的图内重试判定器）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>TimeOverlapRule（ERROR）：同一天各 timeSlot 区间重叠；</li>
 *   <li>OpenHoursRule（ERROR）：timeSlot 必须完全落在候选 openHours 内（无 openHours /
 *       解析失败视为无约束，跳过——确定性降级不误报）；</li>
 *   <li>DurationCapacityRule（ERROR：实际 timeSlot 总时长超可用窗口；WARNING：推荐时长
 *       总和超窗口，仅提示）；</li>
 *   <li>BudgetConsistencyRule（WARNING，不触发重试）：budgetEstimate.ticketCost 与候选
 *       ticketPrice 之和偏差 &gt; 30%。</li>
 * </ul>
 *
 * <p>名称匹配复用 {@link AttractionGroundingChecker#matches}（双向 contains + 后缀归一），
 * 与 M8-2 生成端引用校验同源。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryConflictValidator {

    public static final String SEVERITY_ERROR = "ERROR";
    public static final String SEVERITY_WARNING = "WARNING";

    private final ItineraryConflictCheckProperties properties;

    /** 违规条目（severity=ERROR 触发图内重试；WARNING 仅随输出携带/日志） */
    public record Violation(String rule, String severity, String day,
                            String attraction, String message) {
    }

    /** 行程天（解析 routePlan JSON 的中间形态） */
    record RouteDay(int day, List<RouteAttraction> attractions) {
    }

    /** 行程景点（name/timeSlot/cost/notes） */
    record RouteAttraction(String name, String timeSlot, Double cost, String notes) {
    }

    /** 候选景点（enrich 后 SearchResult 的结构化字段子集） */
    record Candidate(String name, String openHours, Double ticketPrice,
                     Boolean freeEntry, String recommendedDuration) {
    }

    /** 校验时间类冲突（重叠/开放时间/时长容量） */
    public List<Violation> validate(String routePlanJson, String candidatesJson) {
        List<RouteDay> days = parseRoutePlan(routePlanJson);
        Map<String, Candidate> byName = parseCandidates(candidatesJson);
        if (days.isEmpty() || byName.isEmpty()) {
            return List.of();
        }
        List<Violation> out = new ArrayList<>();
        out.addAll(validateOverlap(days));
        out.addAll(validateOpenHours(days, byName));
        out.addAll(validateDurationCapacity(days, byName));
        return out;
    }

    /** 校验费用一致性（WARNING 级；图内 budget_estimation 之后调用） */
    public List<Violation> validateBudgetConsistency(String routePlanJson,
                                                     String candidatesJson,
                                                     String budgetJson) {
        List<RouteDay> days = parseRoutePlan(routePlanJson);
        Map<String, Candidate> byName = parseCandidates(candidatesJson);
        if (days.isEmpty() || byName.isEmpty() || budgetJson == null || budgetJson.isBlank()) {
            return List.of();
        }
        double ticketCost = BudgetJsonParser.extractTicketCost(budgetJson);
        double candidateSum = 0;
        int counted = 0;
        for (RouteDay day : days) {
            for (RouteAttraction a : day.attractions()) {
                Candidate c = findCandidate(byName, a.name());
                if (c == null || c.ticketPrice() == null) {
                    continue;
                }
                candidateSum += Boolean.TRUE.equals(c.freeEntry()) ? 0 : c.ticketPrice();
                counted++;
            }
        }
        if (counted == 0 || candidateSum <= 0) {
            return List.of();
        }
        double deviation = Math.abs(ticketCost - candidateSum) / candidateSum;
        if (deviation > 0.30) {
            return List.of(new Violation("BudgetConsistency", SEVERITY_WARNING, "-", "-",
                    String.format("预算估算门票 %.2f 元 与候选集门票之和 %.2f 元 偏差 %.0f%%（>30%%）",
                            ticketCost, candidateSum, deviation * 100)));
        }
        return List.of();
    }

    /** 是否含 ERROR 级违规（冲突重试触发条件） */
    public static boolean hasError(List<Violation> violations) {
        return violations != null && violations.stream()
                .anyMatch(v -> SEVERITY_ERROR.equals(v.severity()));
    }

    // ==================== 规则实现 ====================

    private List<Violation> validateOverlap(List<RouteDay> days) {
        List<Violation> out = new ArrayList<>();
        for (RouteDay day : days) {
            List<RouteAttraction> sorted = new ArrayList<>(day.attractions());
            sorted.sort((a, b) -> {
                Optional<OpenHoursParser.Hours> ha = OpenHoursParser.parseSlot(a.timeSlot());
                Optional<OpenHoursParser.Hours> hb = OpenHoursParser.parseSlot(b.timeSlot());
                return Long.compare(startSecond(ha), startSecond(hb));
            });
            for (int i = 0; i < sorted.size(); i++) {
                for (int j = i + 1; j < sorted.size(); j++) {
                    var a = sorted.get(i);
                    var b = sorted.get(j);
                    Optional<OpenHoursParser.Hours> ha = OpenHoursParser.parseSlot(a.timeSlot());
                    Optional<OpenHoursParser.Hours> hb = OpenHoursParser.parseSlot(b.timeSlot());
                    if (ha.isEmpty() || hb.isEmpty()) {
                        continue;
                    }
                    if (overlaps(ha.get(), hb.get())) {
                        out.add(new Violation("TimeOverlap", SEVERITY_ERROR, "第" + day.day() + "天",
                                a.name() + " 与 " + b.name(),
                                String.format("%s(%s) 与 %s(%s) 时间段重叠",
                                        a.name(), a.timeSlot(), b.name(), b.timeSlot())));
                    }
                }
            }
        }
        return out;
    }

    private List<Violation> validateOpenHours(List<RouteDay> days, Map<String, Candidate> byName) {
        List<Violation> out = new ArrayList<>();
        for (RouteDay day : days) {
            for (RouteAttraction a : day.attractions()) {
                Candidate c = findCandidate(byName, a.name());
                if (c == null || c.openHours() == null) {
                    continue;
                }
                Optional<OpenHoursParser.Hours> slot = OpenHoursParser.parseSlot(a.timeSlot());
                Optional<OpenHoursParser.Hours> open = OpenHoursParser.parse(c.openHours());
                if (slot.isEmpty() || open.isEmpty()) {
                    continue; // 解析失败视为无约束（确定性降级）
                }
                if (slot.get().open().isBefore(open.get().open())
                        || slot.get().close().isAfter(open.get().close())) {
                    out.add(new Violation("OpenHours", SEVERITY_ERROR, "第" + day.day() + "天",
                            a.name(),
                            String.format("%s timeSlot %s 超出开放时间 %s",
                                    a.name(), a.timeSlot(), c.openHours())));
                }
            }
        }
        return out;
    }

    private List<Violation> validateDurationCapacity(List<RouteDay> days,
                                                     Map<String, Candidate> byName) {
        List<Violation> out = new ArrayList<>();
        for (RouteDay day : days) {
            long slotMinutes = 0;
            long recommendedMinutes = 0;
            int recommendedCount = 0;
            for (RouteAttraction a : day.attractions()) {
                Optional<OpenHoursParser.Hours> slot = OpenHoursParser.parseSlot(a.timeSlot());
                if (slot.isPresent()) {
                    slotMinutes += Duration.between(slot.get().open(), slot.get().close()).toMinutes();
                    if (slotMinutes < 0) {
                        slotMinutes += 24 * 60; // 跨 midnight 槽位按当天容量计
                    }
                }
                Candidate c = findCandidate(byName, a.name());
                Optional<Long> rec = parseRecommendedMinutes(c == null ? null : c.recommendedDuration());
                if (rec.isPresent()) {
                    recommendedMinutes += rec.get();
                    recommendedCount++;
                }
            }
            long window = Duration.between(dayStart(), dayEnd()).toMinutes();
            if (window <= 0) {
                continue;
            }
            if (slotMinutes > window) {
                out.add(new Violation("DurationCapacity", SEVERITY_ERROR, "第" + day.day() + "天",
                        "-", String.format("第%d天 timeSlot 总时长 %d 分钟，超过可用窗口 %d 分钟",
                                day.day(), slotMinutes, window)));
            } else if (recommendedCount > 0 && recommendedMinutes > window) {
                out.add(new Violation("DurationCapacity", SEVERITY_WARNING, "第" + day.day() + "天",
                        "-", String.format("第%d天推荐游玩时长合计 %d 分钟，超过可用窗口 %d 分钟",
                                day.day(), recommendedMinutes, window)));
            }
        }
        return out;
    }

    // ==================== 解析工具 ====================

    private List<RouteDay> parseRoutePlan(String routePlanJson) {
        JsonNode root = readTree(routePlanJson);
        if (root == null || !root.isObject() || !root.has("days")) {
            return List.of();
        }
        JsonNode daysNode = root.get("days");
        if (!daysNode.isArray()) {
            return List.of();
        }
        List<RouteDay> days = new ArrayList<>();
        for (JsonNode d : daysNode) {
            int dayNo = d.path("day").asInt(0);
            List<RouteAttraction> attrs = new ArrayList<>();
            JsonNode attractions = d.path("attractions");
            if (attractions.isArray()) {
                for (JsonNode a : attractions) {
                    String name = a.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    attrs.add(new RouteAttraction(
                            name,
                            a.path("timeSlot").asText(""),
                            a.path("cost").isNumber() ? a.path("cost").asDouble() : null,
                            a.path("notes").asText("")));
                }
            }
            days.add(new RouteDay(dayNo, attrs));
        }
        return days;
    }

    private Map<String, Candidate> parseCandidates(String candidatesJson) {
        JsonNode root = readTree(candidatesJson);
        if (root == null || !root.isArray()) {
            return Map.of();
        }
        Map<String, Candidate> map = new LinkedHashMap<>();
        for (JsonNode n : root) {
            String name = n.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            map.putIfAbsent(name, new Candidate(
                    name,
                    n.hasNonNull("openHours") ? n.path("openHours").asText() : null,
                    n.path("ticketPrice").isNumber() ? n.path("ticketPrice").asDouble() : null,
                    n.path("freeEntry").isBoolean() ? n.path("freeEntry").asBoolean() : null,
                    n.hasNonNull("recommendedDuration") ? n.path("recommendedDuration").asText() : null));
        }
        return map;
    }

    private static Candidate findCandidate(Map<String, Candidate> byName, String routeName) {
        return byName.values().stream()
                .filter(c -> AttractionGroundingChecker.matches(c.name(), routeName))
                .findFirst()
                .orElse(null);
    }

    private static JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.getMapper().readTree(AgentOutputUtils.stripCodeFence(json));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean overlaps(OpenHoursParser.Hours a, OpenHoursParser.Hours b) {
        return a.open().isBefore(b.close()) && b.open().isBefore(a.close());
    }

    private static long startSecond(Optional<OpenHoursParser.Hours> h) {
        return h.map(x -> (long) x.open().toSecondOfDay()).orElse(Long.MAX_VALUE);
    }

    /** 推荐时长解析："3小时"→180、"3-4小时"→240（保守取上限）、"0.5天"→720 */
    private static Optional<Long> parseRecommendedMinutes(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String t = text.trim();
        Matcher m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*[-~至到]\\s*(\\d+(?:\\.\\d+)?)\\s*(小时|天)").matcher(t);
        if (m.find()) {
            double v = Double.parseDouble(m.group(2));
            return Optional.of((long) (v * ("天".equals(m.group(3)) ? 24 * 60 : 60)));
        }
        m = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(小时|天)").matcher(t);
        if (m.find()) {
            double v = Double.parseDouble(m.group(1));
            return Optional.of((long) (v * ("天".equals(m.group(2)) ? 24 * 60 : 60)));
        }
        return Optional.empty();
    }

    private LocalTime dayStart() {
        return LocalTime.parse(properties.getDayStart());
    }

    private LocalTime dayEnd() {
        return LocalTime.parse(properties.getDayEnd());
    }
}
