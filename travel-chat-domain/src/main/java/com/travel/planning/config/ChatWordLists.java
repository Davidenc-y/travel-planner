package com.travel.planning.config;

import com.travel.common.util.JsonUtils;
import com.travel.planning.memory.chat.ChatIntent;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * M18-1：聊天链高频词表统一配置（五组，{@code travel.chat.word-lists.*}）。
 *
 * <p>此前五处硬编码词表（意图分类/规划启发式/会话切片/事实共识/偏好陈述）均为
 * 高频改动项（git 多轮"补一个词"式迭代），且 REFINE/RECALL 词面在两处双份维护。
 * 本类将词表收敛为 <b>yml 单源</b>（application-chat.yml，双进程共享）+ Map 注入；
 * 匹配算法（contains/优先级/正则）保持在各消费方不变——词表是数据，算法是逻辑。</p>
 *
 * <p>启动校验（fail-fast）：必填组缺失/为空 → 启动失败；正则编译失败 → 启动失败；
 * 意图词跨组重复 → WARN（优先级靠前的组生效，语义提示）。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "travel.chat.word-lists")
public class ChatWordLists {

    /** 组1：意图分类规则（Map 注入；消费方按 FUNCTIONAL→PROFILE→CHAT→REFINE→RECALL 固定优先级遍历） */
    private Map<ChatIntent, List<String>> intents = new EnumMap<>(ChatIntent.class);

    /** 组2：规划/回顾启发式（与意图词表语义互补，勿合并——M6-55 Batch 2 结论） */
    private Heuristics heuristics = new Heuristics();

    /** 组3：会话知识切片词表 */
    private Chunker chunker = new Chunker();

    /** 组4：会话事实共识主题词（cities 与 knowledge 侧 18 城对齐——H5 决策） */
    private FactConsolidator fact = new FactConsolidator();

    /** 组5：偏好陈述触发词 + 消费风格映射词 */
    private Preference preference = new Preference();

    @Data
    public static class Heuristics {
        private List<String> planning = new ArrayList<>();
        private List<String> recall = new ArrayList<>();
        private List<String> change = new ArrayList<>();
    }

    @Data
    public static class Chunker {
        private List<String> constraintSubstrings = new ArrayList<>();
        private List<String> constraintRegex = new ArrayList<>();
        private List<String> feedbackPatterns = new ArrayList<>();
        /** 编译后的正则缓存（validation 后可用） */
        private transient List<Pattern> compiledRegex = new ArrayList<>();
    }

    @Data
    public static class FactConsolidator {
        private List<String> cities = new ArrayList<>();
        private List<String> partyPatterns = new ArrayList<>();
        private List<String> stylePatterns = new ArrayList<>();
    }

    @Data
    public static class Preference {
        private List<String> statementKeywords = new ArrayList<>();
        private List<String> styleEconomy = new ArrayList<>();
        private List<String> styleComfort = new ArrayList<>();
    }

    /** 启动校验：必填组 fail-fast；正则编译校验；跨意图重复词告警。 */
    @PostConstruct
    public void validate() {
        if (intents == null || intents.isEmpty()) {
            throw new IllegalStateException("词表缺失: travel.chat.word-lists.intents"
                    + " ——五组词表已收敛为配置单源（classpath:application-chat.yml），拒绝以空词表运行");
        }
        requireGroup(heuristics.getPlanning(), "travel.chat.word-lists.heuristics.planning");
        requireGroup(heuristics.getRecall(), "travel.chat.word-lists.heuristics.recall");
        requireGroup(heuristics.getChange(), "travel.chat.word-lists.heuristics.change");
        requireGroup(chunker.getConstraintSubstrings(), "travel.chat.word-lists.chunker.constraint-substrings");
        requireGroup(chunker.getFeedbackPatterns(), "travel.chat.word-lists.chunker.feedback-patterns");
        requireGroup(chunker.getConstraintRegex(), "travel.chat.word-lists.chunker.constraint-regex");
        for (String regex : chunker.getConstraintRegex()) {
            try {
                chunker.getCompiledRegex().add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("travel.chat.word-lists.chunker.constraint-regex 非法正则: "
                        + regex + " (" + e.getMessage() + ")");
            }
        }
        requireGroup(fact.getCities(), "travel.chat.word-lists.fact.cities");
        requireGroup(fact.getPartyPatterns(), "travel.chat.word-lists.fact.party-patterns");
        requireGroup(fact.getStylePatterns(), "travel.chat.word-lists.fact.style-patterns");
        requireGroup(preference.getStatementKeywords(), "travel.chat.word-lists.preference.statement-keywords");

        // 意图词跨组重复：语义提示（不阻断——优先级靠前的意图生效是既有语义）
        Set<String> seen = new HashSet<>();
        Map<String, ChatIntent> owner = new LinkedHashMap<>();
        for (Map.Entry<ChatIntent, List<String>> e : intents.entrySet()) {
            for (String w : e.getValue()) {
                if (!seen.add(w) && owner.get(w) != e.getKey()) {
                    log.warn("[WordLists] 意图词跨组重复: '{}' 同时在 {} 与 {}（按优先级前者生效）",
                            w, owner.get(w), e.getKey());
                }
                owner.putIfAbsent(w, e.getKey());
            }
        }
        log.info("[WordLists] 词表已加载（单源 yml）：intents={}组/{}词, heuristics={}/{}/{}, "
                        + "chunker={}/{}/{}, fact.cities={}, preference.statement={}",
                intents.size(), intents.values().stream().mapToInt(List::size).sum(),
                heuristics.getPlanning().size(), heuristics.getRecall().size(),
                heuristics.getChange().size(),
                chunker.getConstraintSubstrings().size(), chunker.getConstraintRegex().size(),
                chunker.getFeedbackPatterns().size(),
                fact.getCities().size(), preference.getStatementKeywords().size());
    }

    private static void requireGroup(List<?> list, String key) {
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException("词表缺失: " + key
                    + " ——五组词表已收敛为配置单源（classpath:application-chat.yml），拒绝以空词表运行");
        }
    }

    /**
     * 非 Spring 上下文便捷工厂（单测/工具用）：从随包发行的
     * {@code classpath:application-chat.yml} 加载词表——单源仍是该 yml 文件。
     * 主链路由 Spring 绑定装配，不调用本方法。
     */
    public static ChatWordLists fromShippedYml() {
        try {
            YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
            List<PropertySource<?>> sources = loader.load(
                    "application-chat", new ClassPathResource("application-chat.yml"));
            StandardEnvironment env = new StandardEnvironment();
            sources.forEach(ps -> env.getPropertySources().addFirst(ps));
            ChatWordLists lists = Binder.get(env)
                    .bind("travel.chat.word-lists", ChatWordLists.class)
                    .orElseThrow(() -> new IllegalStateException("application-chat.yml 缺少 travel.chat.word-lists"));
            lists.validate();
            return lists;
        } catch (Exception e) {
            throw new IllegalStateException("加载词表单源失败: " + e.getMessage(), e);
        }
    }

    /** 调试/测试辅助：词表摘要 JSON。 */
    public String summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intents", intents.size());
        m.put("cities", fact.getCities().size());
        return JsonUtils.toJson(m);
    }
}
