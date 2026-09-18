package com.travel.planning.controller;

import com.travel.common.config.GrayReleaseManager;
import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.service.DataQualityService;
import com.travel.planning.service.ReliabilityStatsService;
import com.travel.planning.service.TurnLatencyService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M11-3：可靠性看板（管理员）。毕设无角色列，用配置白名单 travel.admin.user-ids 门控。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/reliability")
@RequiredArgsConstructor
public class AdminReliabilityController {

    private final ReliabilityStatsService reliabilityStatsService;
    private final AdminAccessService adminAccessService;
    /** MI-6：灰度开关只读快照（GrayReleaseManager 聚合 Environment；不含动态写）。 */
    private final GrayReleaseManager grayReleaseManager;
    /** E-5a：数据质量端点聚合（outbox 计数+writeback Stream 积压+对账标记）。 */
    private final DataQualityService dataQualityService;
    /** E-5b：慢轮次端点聚合（t_agent_trace 按模型百分位+Top 明细）。 */
    private final TurnLatencyService turnLatencyService;

    @GetMapping("/stats")
    public R<Map<String, Object>> stats(@RequestParam(defaultValue = "7") Integer days) {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问可靠性看板");
        }
        return R.ok(reliabilityStatsService.stats(days == null ? 7 : days));
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
}
