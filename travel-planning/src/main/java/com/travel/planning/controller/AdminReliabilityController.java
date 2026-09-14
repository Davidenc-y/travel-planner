package com.travel.planning.controller;

import com.travel.common.config.GrayReleaseManager;
import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.service.ReliabilityStatsService;
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
