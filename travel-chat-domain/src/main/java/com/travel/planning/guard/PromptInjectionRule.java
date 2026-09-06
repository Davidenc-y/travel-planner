package com.travel.planning.guard;

import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 注入防护规则（F90；M16-3 词表单源化）。
 *
 * <p>高危关键词（忽略指令/泄露提示词/越权/角色扮演）→ 直接拒绝；
 * 中危关键词 → 放行但告警（后续可升级为清洗）。</p>
 *
 * <p><b>M16-3 起词表单源</b>：关键词表唯一来源为
 * {@code travel.guard.prompt-injection.*} 配置（chat-domain 的
 * {@code classpath:application-chat.yml}，双进程共享）。原代码默认词表已删除——
 * guard 开启而词表缺失时启动快速失败，拒绝以空词表静默放行；
 * {@code travel.guard.enabled=false}（整体回滚开关）时不校验词表。</p>
 */
@Slf4j
@Component
public class PromptInjectionRule implements GuardRule {

    private final GuardProperties properties;

    private List<String> blockKeywords = List.of();
    private List<String> warnKeywords = List.of();

    public PromptInjectionRule(GuardProperties properties) {
        this.properties = properties;
    }

    /**
     * F90/M16-3：从配置加载关键词（yml travel.guard.prompt-injection.*，单源）。
     * guard 开启而高危词表缺失/为空 → 启动失败（fail-fast，防静默失去防护）。
     */
    @PostConstruct
    public void refreshKeywords() {
        if (!properties.isEnabled() || !properties.getPromptInjection().isEnabled()) {
            log.warn("[PromptGuard] 防护已关闭（travel.guard.enabled/prompt-injection.enabled=false），跳过词表校验");
            return;
        }
        List<String> block = properties.getPromptInjection().getBlockKeywords();
        List<String> warn = properties.getPromptInjection().getWarnKeywords();
        if (block == null || block.isEmpty()) {
            throw new IllegalStateException(
                    "travel.guard.prompt-injection.block-keywords 未配置：注入防护词表已收敛为配置单源"
                            + "（classpath:application-chat.yml），拒绝以空词表静默放行");
        }
        this.blockKeywords = List.copyOf(block);
        this.warnKeywords = warn == null ? List.of() : List.copyOf(warn);
        log.info("[PromptGuard] 词表已加载（单源 yml）：block={} 词，warn={} 词",
                blockKeywords.size(), warnKeywords.size());
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
