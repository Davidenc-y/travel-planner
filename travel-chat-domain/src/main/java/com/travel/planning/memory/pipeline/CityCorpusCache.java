package com.travel.planning.memory.pipeline;

import com.travel.common.config.ChatWordLists;
import com.travel.common.result.R;
import com.travel.planning.client.KnowledgeSearchPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * AL-2c（GL-2）：城市语料缓存（TTL 默认 600s）——零/薄语料城市前置止损数据源。
 *
 * <p>数据源：{@link KnowledgeSearchPort#cityCounts()}（AL-2a /api/v1/etl/city-counts，
 * <b>禁直连 DB / 禁绕过 port</b>=白名单反模式）。</p>
 *
 * <p>fail-open 语义（方案 §2.2）：拉取失败/不可达=计数按无处理（get 返回 null、
 * firstThinCity 返回 null）→ 闸门放行原图流，knowledge 故障不阻断规划主链路；
 * AL-2b 面四降级返回空 Map=同语义（空缓存 600s 内放行）。</p>
 *
 * <p><b>AR-7（2026-09-30 审计实弹修复）</b>：firstThinCity 原实现两缺陷——
 * ①零行城市不在 GROUP BY 结果=闸门盲区（苏州/武汉等 7 零城恰是最重空烧源，实测
 * 苏州句 12,988 tokens/轮）；②counts map 迭代序首个文本命中+厚城一票否决——
 * composed 携带会话历史时历史厚城几乎必否决（实测含北京历史的杭州句 30,545 tokens
 * 直漏）。修复语义：以 {@link ChatWordLists#getFact()} 的 QU 城市宇宙（18 城单源）
 * 为扫描面——文本提及的宇宙城市中<b>任一厚语料→放行</b>（保多城消息厚城价值）；
 * <b>全部薄或零行→拦</b>（返回首个命中的薄/零城名）。</p>
 *
 * <p>P0⑱：两键默认值在码零 yml——{@code travel.rag.city-precheck.enabled} 默认 false
 * （E-33，开关在 ChatRoutingStep）、{@code travel.rag.city-precheck.thin-threshold}
 * 默认 10（复审修正 3：5 时西安 5 行 5&lt;5=false 仍空烧；10 覆盖 mock 3/杭州 4/西安 5
 * 全部缺口城，真实城市最低 59 远超）。</p>
 */
@Slf4j
@Component
public class CityCorpusCache {

    private final KnowledgeSearchPort knowledgeSearchPort;
    /** AR-7：QU 城市宇宙（wordLists.fact.cities 单源）——零行城市的已知性来源 */
    private final ChatWordLists wordLists;

    /** TTL（默认 600_000ms=600s；键在码） */
    @Value("${travel.rag.city-precheck.ttl-ms:600000}")
    private long ttlMs = 600_000L;

    /** 薄语料阈值（默认 10；键在码=P0⑱） */
    @Value("${travel.rag.city-precheck.thin-threshold:10}")
    private int thinThreshold = 10;

    /** volatile 快照 + 装载时刻（读多写少，双检同步装载） */
    private volatile Map<String, Integer> counts = Map.of();
    private volatile long loadedAt = 0L;

    public CityCorpusCache(KnowledgeSearchPort knowledgeSearchPort, ChatWordLists wordLists) {
        this.knowledgeSearchPort = knowledgeSearchPort;
        this.wordLists = wordLists;
    }

    /**
     * 城市计数读取：TTL 过期或缓存空→同步拉一次；失败/不可达=返回 null（fail-open 放行）。
     */
    public Integer get(String city) {
        Map<String, Integer> snapshot = ensureLoaded();
        return snapshot.isEmpty() ? null : snapshot.get(city);
    }

    /** 薄语料判定：count!=null && count&lt;threshold（null=不在计数面→由 firstThinCity 宇宙语义处置） */
    public boolean isThin(String city, int threshold) {
        Integer count = get(city);
        return count != null && count < threshold;
    }

    /**
     * AR-7 语义：扫描 QU 城市宇宙中文本提及的城市——任一提及城市厚语料（≥阈值）
     * →null 放行（多城消息的厚城价值保留）；全部提及城市薄或零行（不在计数面）
     * →返回首个命中的薄/零城名（闸门拦截）。文本未提及任何宇宙城市→放行
     * （非 QU 城市目的地不受闸门管辖，防误伤）。
     *
     * <p><b>AR-9（2026-09-30 审计实弹修复）</b>：composed 携带会话历史——历史城市名
     * 两级污染实测（①历史薄城顶替当前城名；②模板话术自带推荐城市名（北京/成都…）
     * →下一轮厚城否决漏网，长沙句 11,915 tokens 实证）。修复=只扫【当前问题】marker
     * 之后的尾段（{@link PlanningHeuristics} tailQuestion 同约定，无 marker 用全文）。</p>
     */
    public String firstThinCity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String q = tailQuestion(text);
        Map<String, Integer> snapshot = ensureLoaded();
        List<String> universe = cityUniverse();
        // AR-7b：判定要素观测行（AK-3b 先例——审计排障面；一轮一条 INFO 不入热路径高频面）
        log.info("[CityPrecheck] 判定要素: snapshot.size={} universe.size={} threshold={} tailLen={}",
                snapshot.size(), universe.size(), thinThreshold, q.length());
        if (snapshot.isEmpty() || universe.isEmpty()) {
            return null;
        }
        String firstThin = null;
        for (String city : universe) {
            if (!q.contains(city)) {
                continue;
            }
            Integer count = snapshot.get(city);
            if (count != null && count >= thinThreshold) {
                return null; // 当前问句提及任一厚城→放行
            }
            if (firstThin == null) {
                firstThin = city; // 薄或零行（不在计数面）→候选拦截
            }
        }
        return firstThin;
    }

    /** 【当前问题】marker 尾段提取（PlanningHeuristics.tailQuestion 同约定；无 marker 用全文）。 */
    private static String tailQuestion(String text) {
        int idx = text.lastIndexOf(com.travel.common.prompt.Markers.CURRENT_QUESTION);
        return idx >= 0 ? text.substring(idx) : text;
    }

    /** QU 城市宇宙（wordLists.fact.cities；缺失防护=空表→放行） */
    private List<String> cityUniverse() {
        try {
            List<String> cities = wordLists.getFact().getCities();
            return cities == null ? List.of() : cities;
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 有语料城市 TOP5（计数降序；模板话术推荐段数据源）。 */
    public List<String> richCities() {
        Map<String, Integer> snapshot = ensureLoaded();
        if (snapshot.isEmpty()) {
            return List.of();
        }
        return snapshot.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() >= thinThreshold)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** 双检装载：过期或空→经 port 同步拉一次；失败保留旧值（fail-open，不抛出）。 */
    private Map<String, Integer> ensureLoaded() {
        Map<String, Integer> snapshot = counts;
        if (snapshot.isEmpty() || System.currentTimeMillis() - loadedAt > ttlMs) {
            synchronized (this) {
                if (counts.isEmpty() || System.currentTimeMillis() - loadedAt > ttlMs) {
                    try {
                        R<Map<String, Integer>> response = knowledgeSearchPort.cityCounts();
                        // AR-8b（2026-09-30 审计实弹修复）：空响应体不再当有效值落 loadedAt——
                        // 原形态（401→Fallback 空 Map→counts=空+时间戳）造成 600s 静默放行窗；
                        // 空体=无效=WARN+不落时间戳=下一轮重试（fail-open 语义不变，可观测性补齐）。
                        if (response != null && response.getData() != null
                                && !response.getData().isEmpty()) {
                            counts = response.getData();
                            loadedAt = System.currentTimeMillis();
                        } else {
                            log.warn("[CityCorpusCache] city-counts 响应空/空体，保留旧值不落时间戳（fail-open，下轮重试）");
                        }
                    } catch (Exception e) {
                        log.warn("[CityCorpusCache] city-counts 拉取失败（fail-open 放行）: {}", e.getMessage());
                    }
                }
                snapshot = counts;
            }
        }
        return snapshot;
    }
}
