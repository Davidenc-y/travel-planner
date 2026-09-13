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
}
