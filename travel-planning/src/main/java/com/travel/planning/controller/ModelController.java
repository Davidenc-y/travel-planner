package com.travel.planning.controller;

import com.travel.aigateway.core.ModelRegistry;
import com.travel.aigateway.route.ModelCircuitGuard;
import com.travel.common.dto.ModelOptionDTO;
import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.service.AdminAccessService;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M7 Batch 2（T12）：模型清单端点 + MM-6 模型注册表只读快照（管理员）。
 *
 * <p>仅返回注册表中 enabled 且 selectable 的模型（D4：embedding/rerank 不出现在
 * 前端清单；D6：未注册/禁用模型入口快速失败由领域层校验）。</p>
 *
 * <p>MM-6：{@code GET /api/v1/admin/models/snapshot} 返回注册表全量描述符（脱敏——
 * 仅 key/roles/enabled/selectable/endpointMode，禁 baseUrl/apiKey）、角色绑定实际
 * 解析结果、ModelCircuitGuard 熔断状态、LegacyModelFallbackConfig 激活标志。
 * 只读（E-22 零写路径）；沿用既有 admin 鉴权口径（AuthUtils + AdminAccessService，
 * 与 gray-release/snapshot 同式）。</p>
 */
@RestController
@RequiredArgsConstructor
public class ModelController {

    private final ModelRegistry modelRegistry;
    private final ModelCircuitGuard modelCircuitGuard;
    private final AdminAccessService adminAccessService;
    private final Environment environment;

    @GetMapping("/api/v1/models")
    public R<List<ModelOptionDTO>> listModels() {
        return R.ok(modelRegistry.listEnabledSelectable().stream()
                .map(d -> new ModelOptionDTO(
                        d.key(), d.displayName(), d.provider().name(), d.selectable()))
                .toList());
    }

    /** MM-6：模型注册表只读快照（管理员；脱敏 + E-22 只读）。 */
    @GetMapping("/api/v1/admin/models/snapshot")
    public R<Map<String, Object>> snapshot() {
        Long userId = AuthUtils.resolveUserId();
        if (!adminAccessService.isAdmin(userId)) {
            throw new BusinessException(40302, "无权访问模型注册表快照");
        }
        List<Map<String, Object>> models = modelRegistry.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("models", models);
        out.put("roleBindings", modelRegistry.roleBindings());
        out.put("circuitStates", modelCircuitGuard.snapshotStates(
                models.stream().map(m -> String.valueOf(m.get("key"))).toList()));
        // LegacyModelFallbackConfig 激活条件 = travel.ai.model-registry.enabled 非 true
        // （@ConditionalOnProperty havingValue=false, matchIfMissing=true 同口径）
        out.put("legacyFallbackActive",
                !"true".equalsIgnoreCase(environment.getProperty("travel.ai.model-registry.enabled")));
        return R.ok(out);
    }
}
