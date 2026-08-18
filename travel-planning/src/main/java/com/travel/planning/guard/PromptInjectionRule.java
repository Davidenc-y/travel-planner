package com.travel.planning.guard;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 注入防护规则（F90）。
 *
 * <p>高危关键词（忽略指令/泄露提示词/越权/角色扮演）→ 直接拒绝；
 * 中危关键词 → 放行但告警（后续可升级为清洗）。关键词表走
 * {@code travel.guard.prompt-injection.*} 配置。</p>
 */
@Slf4j
@Component
public class PromptInjectionRule implements GuardRule {

    private final GuardProperties properties;

    private List<String> blockKeywords = List.of(
            "忽略以上", "忽略之前", "忽略系统", "忽略所有指令", "system prompt", "系统提示词",
            "扮演管理员", "越权", "泄露提示词", "绕过限制", "bypass", "你的指令是什么");

    private List<String> warnKeywords = List.of("不要遵守", "忘记之前的", "假装你是");

    public PromptInjectionRule(GuardProperties properties) {
        this.properties = properties;
    }

    /** F90：从配置刷新关键词（yml travel.guard.prompt-injection.*） */
    @PostConstruct
    public void refreshKeywords() {
        List<String> block = properties.getPromptInjection().getBlockKeywords();
        List<String> warn = properties.getPromptInjection().getWarnKeywords();
        if (block != null && !block.isEmpty()) {
            this.blockKeywords = block;
        }
        if (warn != null && !warn.isEmpty()) {
            this.warnKeywords = warn;
        }
    }

    @Override
    public String name() {
        return "promptInjection";
    }

    @Override
    public GuardResult check(String userId, String input) {
        if (input == null || input.isBlank()) {
            return GuardResult.allow();
        }
        String lower = input.toLowerCase();
        for (String kw : blockKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                log.warn("[PromptGuard] 命中高危注入关键词并拦截: userId={}, keyword={}", userId, kw);
                return GuardResult.deny("输入包含不安全指令，已拦截（命中: " + kw + "）");
            }
        }
        for (String kw : warnKeywords) {
            if (lower.contains(kw.toLowerCase())) {
                log.warn("[PromptGuard] 命中中危关键词（放行并记录）: userId={}, keyword={}", userId, kw);
                break;
            }
        }
        return GuardResult.allow();
    }
}
