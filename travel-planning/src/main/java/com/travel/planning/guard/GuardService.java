package com.travel.planning.guard;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 防护服务入口（F90/F91）：顺序执行启用规则，任一拒绝即拒绝。
 * 总开关关闭时全部放行（回滚语义）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardService {

    private final GuardProperties properties;
    private final List<GuardRule> rules;

    public GuardResult check(String userId, String input) {
        if (!properties.isEnabled()) {
            return GuardResult.allow();
        }
        for (GuardRule rule : rules) {
            GuardResult r = rule.check(userId, input);
            if (!r.allowed()) {
                return r;
            }
        }
        return GuardResult.allow();
    }
}
