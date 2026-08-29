package com.travel.planning.controller;

import com.travel.aigateway.core.ModelRegistry;
import com.travel.common.dto.ModelOptionDTO;
import com.travel.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M7 Batch 2（T12）：模型清单端点。
 *
 * <p>仅返回注册表中 enabled 且 selectable 的模型（D4：embedding/rerank 不出现在
 * 前端清单；D6：未注册/禁用模型入口快速失败由领域层校验）。</p>
 */
@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRegistry modelRegistry;

    @GetMapping
    public R<List<ModelOptionDTO>> listModels() {
        return R.ok(modelRegistry.listEnabledSelectable().stream()
                .map(d -> new ModelOptionDTO(
                        d.key(), d.displayName(), d.provider().name(), d.selectable()))
                .toList());
    }
}
