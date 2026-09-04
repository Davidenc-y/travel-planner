package com.travel.knowledge.rag.support;

import com.travel.common.entity.Attraction;
import com.travel.knowledge.rag.config.RagEnrichmentProperties;
import com.travel.knowledge.rag.model.SearchResult;
import com.travel.knowledge.rag.websearch.WebEnrichExtractor;
import com.travel.knowledge.rag.websearch.WebSearchPort;
import com.travel.knowledge.rag.websearch.WebSearchProperties;
import com.travel.knowledge.repository.AttractionMapper;
import com.travel.knowledge.service.WebEnrichWritebackService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ChatModel;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * M8-1：检索结果结构化事实补全器。
 *
 * <p>背景：ES/Milvus 索引侧不含 openHours/recommendedDuration/address 等结构化字段，
 * MySQL t_attraction 才是事实源。本类在检索出口按 docId（= t_attraction.id 字符串）
 * 批量回查事实源补全结构化字段，避免为加字段重建 Milvus collection / ES reindex。</p>
 *
 * <p>设计约束：
 * <ul>
 *   <li>单条 {@code SELECT ... WHERE id IN (...)}（selectBatchIds，主键索引，毫秒级），
 *       避免 N+1 点查；</li>
 *   <li>查不到的条目保持原样（未来 web 景点 docId 无 MySQL 行）；</li>
 *   <li>docId 非数字容错跳过；</li>
 *   <li>空字符串统一转 null（全链 null=知识库无此数据的语义约定）；</li>
 *   <li>补全失败不抛异常——检索主流程不受影响（降级为无结构化字段）。</li>
 * </ul></p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AttractionEnricher {

    private final AttractionMapper attractionMapper;
    private final RagEnrichmentProperties properties;

    /** M8-2：补全失败降级指标（setter 注入；直构单测为 null 时跳过记录） */
    private RagRoutingMetrics routingMetrics;

    @Autowired(required = false)
    void setRoutingMetrics(RagRoutingMetrics routingMetrics) {
        this.routingMetrics = routingMetrics;
    }

    /** M8-4：联网搜索端口（Noop 默认；enabled=true 时为 MCP 适配器） */
    private WebSearchPort webSearchPort;

    /** M8-4：联网搜索配置（null 时跳过补全，测试直构兼容） */
    private WebSearchProperties webSearchProperties;

    /** M8-4：结构化抽取 lightModel（null 时跳过，测试直构兼容） */
    private ChatModel lightModel;

    private final WebEnrichExtractor webEnrichExtractor = new WebEnrichExtractor();

    @Autowired(required = false)
    void setWebSearchPort(WebSearchPort webSearchPort) {
        this.webSearchPort = webSearchPort;
    }

    @Autowired(required = false)
    void setWebSearchProperties(WebSearchProperties webSearchProperties) {
        this.webSearchProperties = webSearchProperties;
    }

    @Autowired(required = false)
    void setLightModel(@Qualifier("lightModel") ChatModel lightModel) {
        this.lightModel = lightModel;
    }

    /** M8-5：回写闭环（writeback-enabled 门控；null 时跳过） */
    private WebEnrichWritebackService webEnrichWritebackService;

    @Autowired(required = false)
    void setWebEnrichWritebackService(WebEnrichWritebackService webEnrichWritebackService) {
        this.webEnrichWritebackService = webEnrichWritebackService;
    }

    /**
     * 按 docId（= t_attraction.id 字符串）批量回查事实源，补全结构化字段。
     * 关闭开关（travel.rag.enrichment.enabled=false）时原样直通（回滚开关）。
     */
    public List<SearchResult> enrich(List<SearchResult> results) {
        if (results == null || results.isEmpty() || !properties.isEnabled()) {
            return results;
        }
        try {
            Map<Long, Attraction> byId = attractionMapper.selectBatchIds(
                            results.stream()
                                    .map(SearchResult::getDocId)
                                    .map(this::parseId)
                                    .filter(Objects::nonNull)
                                    .toList())
                    .stream()
                    .collect(Collectors.toMap(Attraction::getId, Function.identity()));
            if (byId.isEmpty()) {
                return results;
            }
            int filled = 0;
            for (SearchResult r : results) {
                Long id = parseId(r.getDocId());
                Attraction a = id == null ? null : byId.get(id);
                if (a == null) {
                    continue;
                }
                r.setCity(a.getCity());
                r.setType(a.getType());
                r.setAddress(blankToNull(a.getAddress()));
                r.setOpenHours(blankToNull(a.getOpenHours()));
                r.setTicketPrice(a.getTicketPrice() == null ? null : a.getTicketPrice().doubleValue());
                r.setFreeEntry(a.getFreeEntry() != null && a.getFreeEntry() == 1);
                r.setRating(a.getRating() == null ? null : a.getRating().doubleValue());
                r.setRecommendedDuration(blankToNull(a.getRecommendedDuration()));
                r.setDataSource(a.getSource());
                filled++;
            }
            log.info("[Enrich] 结构化补全完成: 候选={} 条, 命中事实源={} 条", results.size(), filled);
            // M8-4：本地字段缺失时的联网兜底（仅 openHours/ticketPrice；默认关）
            if (webSearchPort != null && webSearchProperties != null
                    && webSearchProperties.isEnabled()) {
                fillMissingFromWeb(results);
            }
        } catch (Exception e) {
            // M8-1：补全失败仅降级（结果无结构化字段），不阻断检索主流程
            log.warn("[Enrich] 结构化补全失败，降级返回原结果: {}", e.getMessage());
            if (routingMetrics != null) {
                routingMetrics.recordDegraded("enrich_fail");
            }
        }
        return results;
    }

    /** docId → t_attraction.id；非数字容错返回 null（该条跳过补全） */
    private Long parseId(String docId) {
        if (docId == null || docId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(docId.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 空字符串统一转 null（null=知识库无此数据；禁止空串） */
    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * M8-4：本地字段缺失时联网补充（仅 enrich-fields 配置字段）。
     *
     * <p>流程：缺失检测（确定性）→ WebSearchPort.search("{name} {city} 开放时间 门票价格")
     * → lightModel 结构化抽取 + 确定性校验 → 标注 dataSource=web_enrich。
     * 同一请求内同景点只补一次（Set 缓存）；失败全部静默降级为 null，
     * 不阻塞主流程（延迟预算：1 次搜索 ≤timeout + 1 次 lightModel 抽取）。</p>
     */
    private void fillMissingFromWeb(List<SearchResult> results) {
        List<String> fields = webSearchProperties.getEnrichFields() == null
                ? List.of() : webSearchProperties.getEnrichFields();
        Set<String> attempted = new HashSet<>();
        for (SearchResult r : results) {
            String id = r.getDocId();
            if (id == null || !attempted.add(id)) {
                continue;
            }
            boolean needOpen = fields.contains("openHours") && r.getOpenHours() == null;
            boolean needPrice = fields.contains("ticketPrice") && r.getTicketPrice() == null;
            if (!needOpen && !needPrice) {
                continue;
            }
            // M9-2：异步补全模式——本轮保持 null，投递后台搜索→抽取→回写
            if (webSearchProperties.isAsyncFillMode()
                    && webSearchProperties.isWritebackEnabled()
                    && webEnrichWritebackService != null) {
                Long attractionId = parseId(id);
                if (attractionId != null
                        && webEnrichWritebackService.submitAsyncFill(
                                attractionId, r.getTitle(), r.getCity())) {
                    log.info("[Enrich] 异步补全已投递（本轮保持 null）: docId={}", attractionId);
                }
                continue;
            }
            try {
                String query = (r.getTitle() == null ? "" : r.getTitle())
                        + (r.getCity() == null ? "" : " " + r.getCity())
                        + " 开放时间 门票价格";
                var result = webSearchPort.search(query);
                if (result.isEmpty()) {
                    continue;
                }
                var fieldsOut = webEnrichExtractor.extract(
                        lightModel, r.getTitle(), r.getCity(), result.get());
                if (fieldsOut.isEmpty()) {
                    continue;
                }
                if (needOpen && fieldsOut.get().openHours() != null) {
                    r.setOpenHours(fieldsOut.get().openHours());
                }
                if (needPrice && fieldsOut.get().ticketPrice() != null) {
                    r.setTicketPrice(fieldsOut.get().ticketPrice());
                }
                if (r.getOpenHours() != null || r.getTicketPrice() != null) {
                    // M8-4：联网补充标注（低置信度，注入侧会显式提示）
                    r.setDataSource("web_enrich");
                    log.info("[Enrich] web 补全成功: id={}, name={}", id, r.getTitle());
                    // M8-5：回写本地（默认关；空字段保护 + 7 天防抖在服务内）
                    if (webSearchProperties.isWritebackEnabled()
                            && webEnrichWritebackService != null) {
                        webEnrichWritebackService.writeback(
                                parseId(id),
                                fieldsOut.get().openHours(),
                                fieldsOut.get().ticketPrice());
                    }
                }
            } catch (Exception e) {
                log.warn("[Enrich] web 补全失败（静默降级）: id={}, err={}", id, e.getMessage());
            }
        }
    }
}
