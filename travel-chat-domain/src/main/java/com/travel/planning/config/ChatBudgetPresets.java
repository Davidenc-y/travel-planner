package com.travel.planning.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S-C2：意图分级预算 presets（方案 01 §S-C/C-3；§八④ 授权新键组 travel.chat.budget-presets）。
 *
 * <p>档位语义（方案定值方向，具体数值为本实现标定、yml 可整体覆盖）：
 * CHAT/PROFILE/FUNCTIONAL 紧、PLANNING/REFINE 标准、RECALL 宽。
 * 消费方（C-2b 接线）：supervisor 墙钟覆盖（F23 全局预算保险丝语义）与
 * TokenUsageInterceptor maxTokens 门；超限走既有 QuotaShortCircuit 通道。</p>
 *
 * <p>yml 缺省=内置默认档（键全量给出，与默认值一致）；若 yml 只覆盖部分意图，
 * 整组替换后缺失意图经 {@link #resolve} 回落 PLANNING 标准档（防误配过宽）。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Component
@ConfigurationProperties(prefix = "travel.chat")
public class ChatBudgetPresets {

    private Map<String, BudgetPreset> budgetPresets = defaultPresets();

    public Map<String, BudgetPreset> getBudgetPresets() {
        return budgetPresets;
    }

    public void setBudgetPresets(Map<String, BudgetPreset> budgetPresets) {
        this.budgetPresets = budgetPresets;
    }

    /** 解析意图档；null/未知意图回落 PLANNING 标准档（防误配过宽）。 */
    public BudgetPreset resolve(String intent) {
        if (intent == null || intent.isBlank()) {
            return standard();
        }
        BudgetPreset preset = budgetPresets.get(intent.trim().toUpperCase());
        return preset != null ? preset : standard();
    }

    private BudgetPreset standard() {
        BudgetPreset preset = budgetPresets.get("PLANNING");
        return preset != null ? preset : new BudgetPreset(8, 45_000L, 10_000);
    }

    /**
     * S-C2c：墙钟 clamp 静态助手（无 bean 场景，如 SupervisorGraphExecutor）——使用内置默认档
     * （yml 覆盖仅 bean 路径可见）。**intent 未分类（null/空）→ 原样返回**（只紧不松，E-33 式）。
     */
    public static long clampWallSeconds(String intent, long existingSeconds) {
        if (intent == null || intent.isBlank()) {
            return existingSeconds;
        }
        BudgetPreset preset = defaultPresets().get(intent.trim().toUpperCase());
        if (preset == null) {
            return existingSeconds;
        }
        return Math.min(preset.getWallMs() / 1000L, existingSeconds);
    }

    /** 内置默认档（yml 缺省时生效）：紧 4 步/20s/4k，标准 8 步/45s/10k，宽 12 步/75s/16k。 */
    public static Map<String, BudgetPreset> defaultPresets() {
        Map<String, BudgetPreset> presets = new LinkedHashMap<>();
        presets.put("CHAT", new BudgetPreset(4, 20_000L, 4_000));
        presets.put("PROFILE", new BudgetPreset(4, 20_000L, 4_000));
        presets.put("FUNCTIONAL", new BudgetPreset(4, 20_000L, 4_000));
        presets.put("PLANNING", new BudgetPreset(8, 45_000L, 10_000));
        presets.put("REFINE", new BudgetPreset(8, 45_000L, 10_000));
        presets.put("RECALL", new BudgetPreset(12, 75_000L, 16_000));
        return presets;
    }

    /** 单意图预算档（maxSteps=图步上限；wallMs=墙钟上限；maxTokens=token 门）。 */
    public static final class BudgetPreset {

        private int maxSteps;
        private long wallMs;
        private int maxTokens;

        public BudgetPreset() {
        }

        public BudgetPreset(int maxSteps, long wallMs, int maxTokens) {
            this.maxSteps = maxSteps;
            this.wallMs = wallMs;
            this.maxTokens = maxTokens;
        }

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public long getWallMs() {
            return wallMs;
        }

        public void setWallMs(long wallMs) {
            this.wallMs = wallMs;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }
    }
}
