package com.travel.planning.controller;

import com.travel.common.config.GrayReleaseManager;
import com.travel.common.event.EventConsumerRegistry;
import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.memory.knowledge.RagQualityCounters;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.service.monitoring.DataQualityService;
import com.travel.planning.service.monitoring.ReliabilityStatsService;
import com.travel.planning.service.monitoring.TurnLatencyService;
import com.travel.planning.util.AuthUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11-3：可靠性看板（管理员）。毕设无角色列，用配置白名单 travel.admin.user-ids 门控。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/reliability")
public class AdminReliabilityController {

    private final ReliabilityStatsService reliabilityStatsService;
    private final AdminAccessService adminAccessService;
    /** MI-6：灰度开关只读快照（GrayReleaseManager 聚合 Environment；不含动态写）。 */
    private final GrayReleaseManager grayReleaseManager;
    /** E-5a：数据质量端点聚合（outbox 计数+writeback Stream 积压+对账标记）。 */
    private final DataQualityService dataQualityService;
    /** E-5b：慢轮次端点聚合（t_agent_trace 按模型百分位+Top 明细）。 */
    private final TurnLatencyService turnLatencyService;
    /** RK-13/D-5：RAG 质量指标 Redis 读（键前缀=chat-domain RagQualityCounters.KEY_PREFIX 唯一权威源）。 */
    private final StringRedisTemplate stringRedisTemplate;
    /** AL-3（GL-3）：事件消费者注册中心（AR-5 计数器的进程内读取面，travel-common @Component 既有装配）。 */
    private final EventConsumerRegistry eventConsumerRegistry;
    /** AY-2：预算限额展示口径（与 ai-gateway LLMBudgetGuard 同键同默认；行为权威在网关）。 */
    private final long dailyTokens;
    private final long hourlyTokens;
    private final boolean budgetEnabled;

    /**
     * AY-2（注入形态修正）：@RequiredArgsConstructor 与 @Value 构造器参数不兼容
     * （Lombok 不处理 @Value，直构测试将拿到 0 限额=项目禁的反模式），改显式构造器——
     * 7 个 final 依赖原序成参 + 3 个 @Value 限额参数（AO 批 LLMBudgetGuard 三阈值同款先例）。
     */
    public AdminReliabilityController(ReliabilityStatsService reliabilityStatsService,
                                      AdminAccessService adminAccessService,
                                      GrayReleaseManager grayReleaseManager,
                                      DataQualityService dataQualityService,
                                      TurnLatencyService turnLatencyService,
                                      StringRedisTemplate stringRedisTemplate,
                                      EventConsumerRegistry eventConsumerRegistry,
                                      @Value("${travel.ai.budget.daily-tokens:3000000}") long dailyTokens,
                                      @Value("${travel.ai.budget.hourly-tokens:600000}") long hourlyTokens,
                                      @Value("${travel.ai.budget.enabled:true}") boolean budgetEnabled) {
        this.reliabilityStatsService = reliabilityStatsService;
        this.adminAccessService = adminAccessService;
        this.grayReleaseManager = grayReleaseManager;
        this.dataQualityService = dataQualityService;
        this.turnLatencyService = turnLatencyService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.eventConsumerRegistry = eventConsumerRegistry;
        this.dailyTokens = dailyTokens;
        this.hourlyTokens = hourlyTokens;
        this.budgetEnabled = budgetEnabled;
    }

    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "7") Integer days) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问可靠性看板");
        }
        return R.ok(reliabilityStatsService.stats(days == null ? 7 : days));
    }

    /** RK-13/D-5：RAG 线上质量指标（近 7 日三指标聚合，Redis 日分片键 0 缺省；只读）。 */
    @GetMapping("/rag-quality")
    public R<Map<String, Object>> ragQuality() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问可靠性看板");
        }
        LocalDate today = LocalDate.now();
        List<String> days = new ArrayList<>();
        Map<String, List<Long>> metrics = new LinkedHashMap<>();
        for (String metric : List.of("abstain", "lowconf", "degraded", "hallucflag")) {
            metrics.put(metric, new ArrayList<>());
        }
        for (int i = 6; i >= 0; i--) {
            String day = today.minusDays(i).toString();
            days.add(day);
            for (Map.Entry<String, List<Long>> e : metrics.entrySet()) {
                String v = stringRedisTemplate.opsForValue()
                        .get(RagQualityCounters.KEY_PREFIX + day + ":" + e.getKey());
                e.getValue().add(parse0(v));
            }
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        metrics.forEach((k, v) -> totals.put(k, v.stream().mapToLong(Long::longValue).sum()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", days);
        body.put("metrics", metrics);
        body.put("totals", totals);
        return R.ok(body);
    }

    /** 0 缺省解析（键缺失=0；非数值容错 0） */
    private static long parse0(String v) {
        if (v == null || v.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** MI-6：灰度开关快照（只读，不含动态写——动态切换留人工批次）。 */
    @GetMapping("/gray-release/snapshot")
    public R<Map<String, Object>> grayReleaseSnapshot() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问灰度快照");
        }
        return R.ok(grayReleaseManager.snapshot());
    }

    /**
     * E-5a：数据质量端点（纯读聚合）——outbox 未消费计数/writeback Stream 积压
     * （死信口径 F 案A：PEL 计数）/三端对账标记（check_consistency 未实现，
     * R19b 显式标记不伪造数据）。
     */
    @GetMapping("/data-quality")
    public R<Map<String, Object>> dataQuality() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问数据质量看板");
        }
        return R.ok(dataQualityService.dataQualitySnapshot());
    }

    /**
     * E-5b：慢轮次端点（纯读聚合）——t_agent_trace 按 model 分组 P50/P95/avg/count
     * + Top 慢轮次明细 10 条（只读 SQL 经 AgentTraceMapper 新增查询方法）。
     */
    @GetMapping("/turn-latency")
    public R<Map<String, Object>> turnLatency(@RequestParam(defaultValue = "7") Integer days) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问慢轮次看板");
        }
        return R.ok(turnLatencyService.turnLatency(days == null ? 7 : days));
    }

    /**
     * MM-8：灰度开关动态写覆盖层（内存覆盖，重启即失效）。
     *
     * <p>E-19④ 人工闸门：开关 {@code travel.gray.dynamic-write.enabled} 默认 false——
     * false 时本端点返回 405、覆盖层 no-op（代码可产出、启用须人工改配置）。
     * 变更审计：GrayReleaseManager 内输出 {@code [GrayOverride]} 日志行。</p>
     */
    @PutMapping("/gray-release/{key}")
    public R<Map<String, Object>> overrideGrayRelease(@PathVariable String key,
                                                      @RequestParam boolean on) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问灰度动态写");
        }
        if (!grayReleaseManager.isDynamicWriteEnabled()) {
            // MM-8-fix（二次审批）：ResponseStatusException 不被 GlobalExceptionHandler 识别（吞成
            // 50000/HTTP 500），改走 BusinessException 通道——http-status-aligned 下 HTTP 405 可达客户端
            throw new BusinessException(40501, "灰度动态写未启用（travel.gray.dynamic-write.enabled=false）");
        }
        if (!grayReleaseManager.override(key, on)) {
            throw new BusinessException(40001, "未知灰度键: " + key);
        }
        return R.ok(grayReleaseManager.snapshot());
    }

    /**
     * AL-3（GL-3）：事件消费者注册中心观测面外露（只读透传 status() 行列表——
     * channel/key/grayKey/running/lastConsumeAt/consumeCount/rejectCount；
     * AR-5 计数器此前仅进程内可读，此端点补齐观测闭环；零新依赖零新装配）。
     */
    @GetMapping("/event-consumers")
    public R<List<EventConsumerRegistry.ConsumerStatus>> eventConsumers() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问消费者注册中心");
        }
        return R.ok(eventConsumerRegistry.status());
    }

    /** AY-2：LLM 预算水位（只读）——权威口径=Redis 共享键 ai:budget:*（AK-3b/AX 审计
     * 结论）；限额键与 ai-gateway LLMBudgetGuard 同源同默认（展示近似，行为权威在网关）。
     * Redis 异常 fail-open：used=0 + degraded=true（不阻断看板）。 */
    @GetMapping("/budget")
    public R<Map<String, Object>> budget() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问可靠性看板");
        }
        String dayKey = "ai:budget:d:" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String hourKey = "ai:budget:h:" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        Map<String, Object> body = new LinkedHashMap<>();
        boolean degraded = false;
        try {
            body.put("dailyUsed", parse0(stringRedisTemplate.opsForValue().get(dayKey)));
            body.put("hourlyUsed", parse0(stringRedisTemplate.opsForValue().get(hourKey)));
        } catch (Exception e) {
            degraded = true;
            body.put("dailyUsed", 0L);
            body.put("hourlyUsed", 0L);
        }
        body.put("dailyLimit", dailyTokens);
        body.put("hourlyLimit", hourlyTokens);
        body.put("enabled", budgetEnabled);
        body.put("dayKey", dayKey);
        body.put("hourKey", hourKey);
        body.put("degraded", degraded);
        return R.ok(body);
    }
}
