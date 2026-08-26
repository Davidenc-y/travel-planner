package com.travel.planning.guard;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 安全防护配置（F90/F91）：对应 yml {@code travel.guard.*} */
@Data
@Component
@ConfigurationProperties(prefix = "travel.guard")
public class GuardProperties {

    /** 总开关；false → 全部防护关闭（回滚开关） */
    private boolean enabled = true;

    private PromptInjection promptInjection = new PromptInjection();

    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class PromptInjection {
        private boolean enabled = true;
        private List<String> blockKeywords = new ArrayList<>();
        private List<String> warnKeywords = new ArrayList<>();
    }

    @Data
    public static class CircuitBreaker {
        private int failureThreshold = 3;
        private long windowMs = 60_000;
        private long openTimeoutMs = 15_000;
    }
}
