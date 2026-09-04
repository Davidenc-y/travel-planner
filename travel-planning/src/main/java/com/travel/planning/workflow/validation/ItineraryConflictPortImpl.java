package com.travel.planning.workflow.validation;

import com.travel.planning.agent.support.ItineraryConflictPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M9-4：ItineraryConflictPort 的 planning 实现（父容器注入 chat-domain 消费方）。
 *
 * <p>纯委托 {@link ItineraryConflictValidator#validate}；输入不可解析/校验异常
 * 一律返回空列表（观测级容错，不阻断回答）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryConflictPortImpl implements ItineraryConflictPort {

    private final ItineraryConflictValidator validator;

    @Override
    public List<Map<String, String>> validate(String routePlanJson, String candidatesJson) {
        try {
            return validator.validate(routePlanJson, candidatesJson).stream()
                    .map(v -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("rule", v.rule());
                        m.put("severity", v.severity());
                        m.put("day", v.day());
                        m.put("attraction", v.attraction());
                        m.put("message", v.message());
                        return m;
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("[ItineraryConflictPort] 聊天路径冲突校验异常（观测降级为空）: {}",
                    e.getMessage());
            return List.of();
        }
    }
}
