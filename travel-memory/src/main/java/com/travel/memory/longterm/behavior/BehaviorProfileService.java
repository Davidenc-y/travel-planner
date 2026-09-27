package com.travel.memory.longterm.behavior;

import com.travel.common.entity.UserBehaviorProfile;
import com.travel.common.entity.AgentTrace;
import com.travel.common.entity.ChatSession;
import com.travel.common.util.JsonUtils;
import com.travel.common.repository.AgentTraceMapper;
import com.travel.memory.longterm.ProfileSlotPort;
import com.travel.memory.repository.ChatSessionMapper;
import com.travel.memory.repository.UserBehaviorProfileMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * M17-2：行为画像重算服务（零新增埋点——聚合既有行为数据）。
 *
 * <p>数据源：t_agent_trace（活跃时段直方图，start_time 毫秒精度）、
 * t_chat_session（会话数）、行程事实（经 {@link TripFactsPort}：目的地频次/
 * 预算分位/同伴众数/REFINE 倾向，planning 进程提供；webflux 无实现时行程维度
 * 自动降级为空）。</p>
 *
 * <p>读取策略：懒重算——{@link #getBehavior(Long)} 命中新鲜行（stale-after-minutes
 * 内）直接复用；否则同步重算（有界查询）后返回。写入策略：行程完成（recordTrip）
 * 后经 LlmGovernor 后台异步重算（触发点在 TravelProfileService）；失败静默（观测
 * 日志），下次读取/触发重试。</p>
 */
@Slf4j
@Service
public class BehaviorProfileService {

    private final UserBehaviorProfileMapper behaviorMapper;
    private final AgentTraceMapper traceMapper;
    private final ChatSessionMapper sessionMapper;
    private final ObjectProvider<TripFactsPort> tripFactsPort;
    private final BehaviorProfileProperties props;
    /** AB-5d：画像结构化端口（optional；缺省=slot/model 查询返回空，E-33 关闭态零行为）。 */
    private final ObjectProvider<ProfileSlotPort> profileSlotPort;

    /** 单用户重算单飞（防并发重复聚合） */
    private final ConcurrentHashMap<Long, Boolean> inFlight = new ConcurrentHashMap<>();

    public BehaviorProfileService(UserBehaviorProfileMapper behaviorMapper,
                                   AgentTraceMapper traceMapper,
                                   ChatSessionMapper sessionMapper,
                                   ObjectProvider<TripFactsPort> tripFactsPort,
                                   BehaviorProfileProperties props,
                                   ObjectProvider<ProfileSlotPort> profileSlotPort) {
        this.behaviorMapper = behaviorMapper;
        this.traceMapper = traceMapper;
        this.sessionMapper = sessionMapper;
        this.tripFactsPort = tripFactsPort;
        this.props = props;
        this.profileSlotPort = profileSlotPort;
    }

    // AC-2b：推断回写接线依赖——optional setter 注入（AC-1a/AC-1c 先例，6 参构造零变动）；
    // E-33：travel.profile.style-inference-enabled 与 planning yml 同键（默认 false=零回写）
    private ObjectProvider<InferredStylePort> inferredStylePort;

    @Value("${travel.profile.style-inference-enabled:false}")
    private boolean styleInferenceEnabled = false;

    @Autowired(required = false)
    void setInferredStylePort(ObjectProvider<InferredStylePort> inferredStylePort) {
        this.inferredStylePort = inferredStylePort;
    }

    void setInferredStylePortForTest(ObjectProvider<InferredStylePort> inferredStylePort,
                                     boolean styleInferenceEnabled) {
        this.inferredStylePort = inferredStylePort;
        this.styleInferenceEnabled = styleInferenceEnabled;
    }

    /**
     * AB-5d：6 段时段画像（零填充口径=方案"LEFT JOIN 全段返回零填充"——无行段
     * useCount=0/topTag/topModel=null；slot 0~5 之外行防御性忽略；端口缺省=空列表）。
     */
    public List<SlotBucket> getSlotProfile(Long userId) {
        ProfileSlotPort port = profileSlotPort != null ? profileSlotPort.getIfAvailable() : null;
        if (port == null || userId == null) {
            return List.of();
        }
        Map<Integer, ProfileSlotPort.SlotUsage> bySlot = new LinkedHashMap<>();
        for (ProfileSlotPort.SlotUsage row : port.querySlots(userId)) {
            if (row.slotId() >= 0 && row.slotId() <= 5) {
                bySlot.put(row.slotId(), row);
            }
        }
        List<SlotBucket> buckets = new ArrayList<>(6);
        for (int slot = 0; slot < 6; slot++) {
            ProfileSlotPort.SlotUsage row = bySlot.get(slot);
            buckets.add(row == null
                    ? new SlotBucket(slot, 0, null, null)
                    : new SlotBucket(slot, row.useCount(),
                        topOfJsonArray(row.preferredTags(), "tag"),
                        topOfJsonArray(row.preferredModels(), "model")));
        }
        return buckets;
    }

    /**
     * AB-5d：按模型聚合摘要（sum 次数/sum tokens/ttft 按行 useCount 加权平均——
     * 仅对非 null ttft 行计权；<b>slot=-1 汇总行排除</b>：其定义为"不限时段汇总行"，
     * 并入会与分时段行双计——裁定记录）。
     */
    public List<ModelSummary> getModelUsageSummary(Long userId) {
        ProfileSlotPort port = profileSlotPort != null ? profileSlotPort.getIfAvailable() : null;
        if (port == null || userId == null) {
            return List.of();
        }
        // acc: [0]=useCount 累计, [1]=tokens 累计, [2]=ttft 加权和, [3]=ttft 权重
        Map<String, long[]> acc = new LinkedHashMap<>();
        for (ProfileSlotPort.ModelUsage row : port.queryModelUsage(userId)) {
            if (row.slotId() == -1) {
                continue;
            }
            long[] a = acc.computeIfAbsent(row.modelKey(), k -> new long[4]);
            a[0] += row.useCount();
            a[1] += row.totalTokens();
            if (row.avgTtftMs() != null && row.useCount() > 0) {
                a[2] += (long) row.avgTtftMs() * row.useCount();
                a[3] += row.useCount();
            }
        }
        List<ModelSummary> out = new ArrayList<>(acc.size());
        acc.forEach((key, a) -> out.add(new ModelSummary(key, a[0], a[1],
                a[3] == 0 ? null : (double) a[2] / a[3])));
        return out;
    }

    /** AB-5d：单时段桶（零填充形态：无行段 useCount=0/top=null）。 */
    public record SlotBucket(int slotId, int useCount, String topTag, String topModel) {
    }

    /** AB-5d：单模型聚合摘要（avgTtftMs=null=无有效 ttft 行）。 */
    public record ModelSummary(String modelKey, long useCount, long totalTokens,
                               Double avgTtftMs) {
    }

    /**
     * AB-5d：[{"tag|model":"x","cnt":n},...] 取 cnt 最大项的键值
     * （解析失败/空/无文本键=null 容忍；cnt 缺省视为 0）。
     */
    static String topOfJsonArray(String json, String keyField) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JsonUtils.getMapper().readTree(json);
            if (!arr.isArray()) {
                return null;
            }
            String best = null;
            int bestCnt = -1;
            for (com.fasterxml.jackson.databind.JsonNode item : arr) {
                com.fasterxml.jackson.databind.JsonNode key = item.get(keyField);
                if (key == null || !key.isTextual()) {
                    continue;
                }
                com.fasterxml.jackson.databind.JsonNode cnt = item.get("cnt");
                int c = cnt != null && cnt.canConvertToInt() ? cnt.asInt() : 0;
                if (c > bestCnt) {
                    bestCnt = c;
                    best = key.asText();
                }
            }
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取行为画像（compute-enabled=false 时恒为 empty；新鲜行复用；过期懒重算）。
     * 重算失败时降级返回过期行（若有），否则 empty。
     */
    public java.util.Optional<UserBehaviorProfile> getBehavior(Long userId) {
        if (userId == null || userId <= 0 || !props.isComputeEnabled()) {
            return java.util.Optional.empty();
        }
        UserBehaviorProfile row = behaviorMapper.findByUserId(userId);
        if (isFresh(row)) {
            return java.util.Optional.of(row);
        }
        recompute(userId);
        return java.util.Optional.ofNullable(behaviorMapper.findByUserId(userId));
    }

    /** 行程完成后触发（观测期 compute-enabled=true；内部自检开关与单飞）。 */
    public void recomputeIfEnabled(Long userId) {
        if (userId == null || userId <= 0 || !props.isComputeEnabled()) {
            return;
        }
        UserBehaviorProfile row = recompute(userId);
        applyInferredStyleIfEnabled(userId, row);
    }

    /**
     * AC-2b：推断→回写接线（recompute 成功收口后调；仅 recomputeIfEnabled 触发点回写，
     * getBehavior 懒重算路径不回写）。E-33：flag 默认关=零回写；推断双 null（样本不足，
     * AC-2a 规则）不调端口；整段 try-catch fail-open 不影响重算主链路（P0⑥ 同构）。
     */
    void applyInferredStyleIfEnabled(Long userId, UserBehaviorProfile row) {
        if (!styleInferenceEnabled || inferredStylePort == null || row == null) {
            return;
        }
        try {
            String style = inferTravelStyle(row);
            String consume = inferConsumeLevel(row);
            if (style == null && consume == null) {
                return;
            }
            InferredStylePort port = inferredStylePort.getIfAvailable();
            if (port != null) {
                port.applyInferredStyle(userId, style, consume);
            }
        } catch (Exception e) {
            log.warn("[BehaviorProfile] 风格推断回写失败（不影响重算）: userId={}, error={}",
                    userId, e.getMessage());
        }
    }

    /** 同步重算（有界查询 + upsert；异常不外抛）。AC-2b：返回成功重算行（失败/单飞让位=null，供回写接线消费）。 */
    public UserBehaviorProfile recompute(Long userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        if (inFlight.putIfAbsent(userId, Boolean.TRUE) != null) {
            return null; // 已有重算在进行
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime since = now.minusDays(props.getStatsWindowDays());

            List<LocalDateTime> traceTimes = loadTraceTimes(userId, since, now);
            long sessionCount = countSessions(userId, since);
            List<TripFactsPort.TripFact> trips = loadTrips(userId, since);

            Aggregated agg = aggregate(traceTimes, sessionCount, trips,
                    props.getStatsWindowDays(), now);

            // 门禁2评审修复2：delete+insert 取代 updateById——MyBatis-Plus 默认 NOT_NULL
            // 策略下 apply 置 null 的维度（窗口滑出后的陈旧 party/budget/dest）不会清列；
            // 单行派生表 delete+insert 无并发损失（单飞 + 单用户低频）
            behaviorMapper.deleteById(userId);
            UserBehaviorProfile row = new UserBehaviorProfile();
            row.setUserId(userId);
            apply(row, agg, props.getStatsWindowDays(), now);
            behaviorMapper.insert(row);
            log.info("[BehaviorProfile] 重算完成: userId={}, trips={}, sessions={}, peak={}",
                    userId, agg.tripCount, agg.sessionCount, activeHourPeakText(agg.activeHours));
            return row;
        } catch (Exception e) {
            log.warn("[BehaviorProfile] 重算失败（不影响主流程，下次重试）: userId={}, error={}",
                    userId, e.getMessage());
            return null;
        } finally {
            inFlight.remove(userId);
        }
    }

    // ---------------- 数据加载（有界） ----------------

    private List<LocalDateTime> loadTraceTimes(Long userId, LocalDateTime since, LocalDateTime now) {
        QueryWrapper<AgentTrace> wrapper = new QueryWrapper<AgentTrace>()
                .eq("user_id", userId)
                .ge("start_time", since)
                .le("start_time", now)
                .select("start_time")
                .last("LIMIT " + props.getTraceScanLimit());
        List<AgentTrace> rows = traceMapper.selectList(wrapper);
        List<LocalDateTime> times = new ArrayList<>(rows.size());
        for (AgentTrace t : rows) {
            if (t.getStartTime() != null) {
                times.add(t.getStartTime());
            }
        }
        return times;
    }

    private long countSessions(Long userId, LocalDateTime since) {
        return sessionMapper.selectCount(new QueryWrapper<ChatSession>()
                .eq("user_id", userId)
                .ge("created_at", since));
    }

    private List<TripFactsPort.TripFact> loadTrips(Long userId, LocalDateTime since) {
        TripFactsPort port = tripFactsPort.getIfAvailable();
        if (port == null) {
            return List.of(); // webflux 等进程：行程维度降级为空
        }
        List<TripFactsPort.TripFact> trips = port.recentTrips(userId, since);
        return trips == null ? List.of() : trips;
    }

    // ---------------- 聚合（纯函数，单测入口） ----------------

    /** 聚合结果（持久化前形态；JSON 序列化在 apply 边界完成） */
    public record Aggregated(
            int[] activeHours,
            BigDecimal planWeeklyAvg,
            BigDecimal budgetP25,
            BigDecimal budgetP50,
            BigDecimal budgetP75,
            List<Map<String, Object>> destFreq,
            String partyMode,
            Integer partyConfidence,
            BigDecimal refineAvg,
            int tripCount,
            long sessionCount) {
    }

    /** 纯函数聚合：不触碰任何存储。 */
    static Aggregated aggregate(List<LocalDateTime> traceTimes, long sessionCount,
                                List<TripFactsPort.TripFact> trips, int windowDays,
                                LocalDateTime now) {
        // 1) 活跃时段直方图（24 维）
        int[] hours = new int[24];
        for (LocalDateTime t : traceTimes) {
            hours[t.getHour()]++;
        }

        int tripCount = trips.size();

        // 2) 周均规划次数（窗口周数下限 1）
        long days = Math.max(Duration.between(now.minusDays(windowDays), now).toDays(), 1);
        BigDecimal weekly = BigDecimal.valueOf(tripCount)
                .multiply(BigDecimal.valueOf(7))
                .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);

        // 3) 预算分位（P25/P50/P75）
        List<BigDecimal> budgets = trips.stream()
                .map(TripFactsPort.TripFact::budget)
                .filter(b -> b != null && b.signum() > 0)
                .sorted()
                .toList();
        BigDecimal p25 = quantile(budgets, 0.25);
        BigDecimal p50 = quantile(budgets, 0.50);
        BigDecimal p75 = quantile(budgets, 0.75);

        // 4) 目的地频次（计数 + 最近时间，最多 8 项按计数倒序）
        Map<String, int[]> destCount = new LinkedHashMap<>();
        Map<String, LocalDateTime> destLast = new LinkedHashMap<>();
        for (TripFactsPort.TripFact t : trips) {
            String city = t.destination() == null ? "" : t.destination().trim();
            if (city.isEmpty()) {
                continue;
            }
            destCount.computeIfAbsent(city, k -> new int[1])[0]++;
            destLast.merge(city, t.createdAt() == null ? LocalDateTime.MIN : t.createdAt(),
                    (a, b) -> a.isAfter(b) ? a : b);
        }
        List<Map<String, Object>> destFreq = new ArrayList<>();
        destCount.entrySet().stream()
                .sorted(Map.Entry.<String, int[]>comparingByValue(
                                Comparator.comparingInt(a -> a[0])).reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(8)
                .forEach(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("city", e.getKey());
                    item.put("cnt", e.getValue()[0]);
                    LocalDateTime last = destLast.get(e.getKey());
                    item.put("lastAt", last == LocalDateTime.MIN ? null : last.toString());
                    destFreq.add(item);
                });

        // 5) 同伴众数与置信度
        Map<String, int[]> partyCount = new LinkedHashMap<>();
        int partyTotal = 0;
        for (TripFactsPort.TripFact t : trips) {
            String p = t.party() == null ? "" : t.party().trim();
            if (p.isEmpty()) {
                continue;
            }
            partyCount.computeIfAbsent(p, k -> new int[1])[0]++;
            partyTotal++;
        }
        String partyMode = null;
        Integer partyConfidence = null;
        if (partyTotal > 0) {
            Map.Entry<String, int[]> best = partyCount.entrySet().stream()
                    .max(Map.Entry.comparingByValue(Comparator.comparingInt(a -> a[0])))
                    .orElse(null);
            if (best != null) {
                partyMode = best.getKey();
                partyConfidence = Math.round(best.getValue()[0] * 100f / partyTotal);
            }
        }

        // 6) REFINE 倾向：平均每行程版本增量（versions-1 的均值，≥0）
        BigDecimal refineAvg = null;
        if (tripCount > 0) {
            long totalExtra = trips.stream().mapToLong(t -> Math.max(t.versions() - 1, 0)).sum();
            refineAvg = BigDecimal.valueOf(totalExtra)
                    .divide(BigDecimal.valueOf(tripCount), 2, RoundingMode.HALF_UP);
        }

        return new Aggregated(hours, weekly, p25, p50, p75, destFreq,
                partyMode, partyConfidence, refineAvg, tripCount, sessionCount);
    }

    private static BigDecimal quantile(List<BigDecimal> sorted, double q) {
        if (sorted.isEmpty()) {
            return null;
        }
        int idx = (int) Math.min(Math.floor(q * sorted.size()), sorted.size() - 1);
        return sorted.get(idx);
    }

    private static void apply(UserBehaviorProfile row, Aggregated agg, int windowDays,
                              LocalDateTime now) {
        row.setActiveHours(JsonUtils.toJson(agg.activeHours()));
        row.setPlanWeeklyAvg(agg.planWeeklyAvg());
        row.setBudgetP25(agg.budgetP25());
        row.setBudgetP50(agg.budgetP50());
        row.setBudgetP75(agg.budgetP75());
        row.setDestFreq(agg.destFreq().isEmpty() ? null : JsonUtils.toJson(agg.destFreq()));
        row.setPartyMode(agg.partyMode());
        row.setPartyConfidence(agg.partyConfidence());
        row.setRefineAvg(agg.refineAvg());
        row.setTripCount(agg.tripCount());
        row.setSessionCount((int) Math.min(agg.sessionCount(), Integer.MAX_VALUE));
        row.setStatsWindowDays(windowDays);
        row.setComputedAt(now);
    }

    private boolean isFresh(UserBehaviorProfile row) {
        return row != null && row.getComputedAt() != null
                && row.getComputedAt().isAfter(LocalDateTime.now().minusMinutes(
                        Math.max(props.getStaleAfterMinutes(), 1)));
    }

    // ---------------- M17-3 注入将消费的派生文本 ----------------

    /** 活跃峰值时段文本（3 小时滑动窗最大计数），如 "20-23点"；无数据 null。 */
    public static String activeHourPeakText(int[] hours) {
        if (hours == null || hours.length != 24) {
            return null;
        }
        int total = Arrays.stream(hours).sum();
        if (total == 0) {
            return null;
        }
        int best = 0;
        int bestSum = -1;
        for (int s = 0; s + 3 <= 24; s++) {
            int sum = hours[s] + hours[s + 1] + hours[s + 2];
            if (sum > bestSum) {
                bestSum = sum;
                best = s;
            }
        }
        if (bestSum <= 0) {
            return null;
        }
        return best + "-" + (best + 3) + "点";
    }

    /** 可靠性门槛（D-Q2）：行程数或会话数达门槛才可注入。 */
    public boolean isReliable(UserBehaviorProfile row) {
        if (row == null) {
            return false;
        }
        boolean tripsOk = row.getTripCount() != null && row.getTripCount() >= props.getMinTrips();
        boolean sessionsOk = row.getSessionCount() != null
                && row.getSessionCount() >= props.getMinSessions();
        return tripsOk || sessionsOk;
    }

    // ==== AC-2a：行为→风格推断引擎（纯静态函数；接线归 AC-2b，E-33 默认关）====

    /** AC-2a：travel_style 推断分位阈值（方案 §一 AC-2a 硬编码：p75<2000 经济 / 2000~8000 舒适 / >8000 豪华） */
    static final BigDecimal STYLE_P75_ECO_UPPER = new BigDecimal("2000");
    static final BigDecimal STYLE_P75_LUX_LOWER = new BigDecimal("8000");
    /** AC-2a：consume_level 推断分位阈值（p50 三档：方案"类似映射"具体化，设计依据=预算分位数与档位经验对齐） */
    static final BigDecimal CONSUME_P50_ECO_UPPER = new BigDecimal("1500");
    static final BigDecimal CONSUME_P50_STD_UPPER = new BigDecimal("5000");
    /** AC-2a：样本量门槛（方案：trip_count<3→null，样本不足不推断） */
    static final int INFERENCE_MIN_TRIPS = 3;

    /**
     * AC-2a：budget_p75 分位数 → travel_style 推断（纯函数零副作用；接线归 AC-2b）。
     *
     * <p><b>值域裁定（非 P0 留痕）</b>：方案写 "&lt;2000→ECONOMICAL"，但既有校验器
     * F70（{@code isValidTravelStyle}）合法值域=ECONOMY/COMFORT/LUXURY——推断输出若为
     * ECONOMICAL 将被写路径校验拒绝。以既有值域为准：输出 ECONOMY。</p>
     *
     * @return ECONOMY/COMFORT/LUXURY；trip_count&lt;3 或 budget_p75 缺失=null（样本不足不推断）
     */
    public static String inferTravelStyle(UserBehaviorProfile p) {
        if (p == null || p.getTripCount() == null || p.getTripCount() < INFERENCE_MIN_TRIPS) {
            return null;
        }
        BigDecimal p75 = p.getBudgetP75();
        if (p75 == null) {
            return null;
        }
        if (p75.compareTo(STYLE_P75_ECO_UPPER) < 0) {
            return "ECONOMY";
        }
        if (p75.compareTo(STYLE_P75_LUX_LOWER) > 0) {
            return "LUXURY";
        }
        return "COMFORT";
    }

    /**
     * AC-2a：budget_p50 分位数 → consume_level 推断（纯函数零副作用；接线归 AC-2b）。
     *
     * <p><b>值域裁定（非 P0 留痕）</b>：方案写"ECONOMICAL/STANDARD/PREMIUM/DELUXE"四档，
     * 但既有校验器 M11-4（{@code isValidConsumeLevel}）合法值域仅
     * ECONOMICAL/STANDARD/COMFORT 三档（无 PREMIUM/DELUXE）——按合法值域三档映射，
     * 原方案高档并入 COMFORT（当前列值域的最高合法档）。</p>
     *
     * @return ECONOMICAL/STANDARD/COMFORT；trip_count&lt;3 或 budget_p50 缺失=null
     */
    public static String inferConsumeLevel(UserBehaviorProfile p) {
        if (p == null || p.getTripCount() == null || p.getTripCount() < INFERENCE_MIN_TRIPS) {
            return null;
        }
        BigDecimal p50 = p.getBudgetP50();
        if (p50 == null) {
            return null;
        }
        if (p50.compareTo(CONSUME_P50_ECO_UPPER) < 0) {
            return "ECONOMICAL";
        }
        if (p50.compareTo(CONSUME_P50_STD_UPPER) > 0) {
            return "COMFORT";
        }
        return "STANDARD";
    }
}
