package com.travel.planning.memory.knowledge;

import com.travel.memory.MemoryFacade;
import com.travel.memory.prompt.PromptTemplates;
import com.travel.memory.repository.ConsolidationLedger;
import com.travel.memory.repository.ConsolidationLedgerMapper;
import com.travel.planning.client.KnowledgeSearchPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import io.micrometer.core.instrument.MeterRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MR-C1：会话记忆整合服务一期（去重 + 时间戳旧删新留；文章 §2.6 环节一二）。
 *
 * <p>数据源（2026-09-18 自审裁决修订，见方案 03 C1 小节）：写入侧留痕台账——
 * {@link SessionKnowledgeWriter} 落 chunk 时经 {@link #recordChunk} 登记（有界内存台账，
 * 跨重启清空=覆盖本进程增量；全量回扫需 knowledge 列举端点，列入待二次审批）。
 * 定时批扫（{@code travel.memory.consolidation.cron}，默认每日 04:00）按 (sessionId,type)
 * 分组 → light 模型判语义重复（dedup prompt）→ 组内 createdAt 旧者按<b>精确 seq</b>
 * {@link KnowledgeSearchPort#deleteSessionContextByPrefix} 删除。</p>
 *
 * <p>红线：<b>仅删不加，不改语义内容</b>（P0⑪）；门控关（{@code enabled=false}，默认）
 * 时 recordChunk 直接丢弃、批扫 no-op（E-33）；light 失败/解析失败 fail-open 零删除。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Component
public class MemoryConsolidationServiceImpl implements MemoryConsolidationService {

    /** 台账容量上限（有界防泄漏；溢出淘汰最旧） */
    static final int LEDGER_CAP = 2000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** MR-C2：蒸馏产物 seq 统一前缀（先删后增幂等的定向清空锚点） */
    static final String DISTILL_SEQ_PREFIX = "distill:";

    /** MR-C2：单会话蒸馏取用的高价值切片上限 */
    private static final int DISTILL_CHUNK_LIMIT = 10;

    private final KnowledgeSearchPort knowledgeClient;
    private final ChatModel lightModel;
    private final PromptTemplates promptTemplates;
    private final MemoryFacade memoryFacade;
    /** RK-12/D-3：巩固可观测（可空 provider，测试直构传 null=无指标零影响） */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final boolean enabled;
    private final int batchSize;
    private final boolean distillEnabled;

    private final Deque<LedgerEntry> ledger = new ArrayDeque<>();

    /** AP-B1：台账库双写 Mapper（optional setter 注入——既有 8 参手工构造零破坏；null=双写跳过，内存态语义零变化=P0㉘） */
    private ConsolidationLedgerMapper ledgerMapper;

    @Autowired(required = false)
    void setLedgerMapper(ConsolidationLedgerMapper ledgerMapper) {
        this.ledgerMapper = ledgerMapper;
    }

    /** 台账条目（写入侧 chunk 快照） */
    record LedgerEntry(String sessionId, String type, String seq, String content, String createdAt) {
    }

    public MemoryConsolidationServiceImpl(KnowledgeSearchPort knowledgeClient,
                                      @Qualifier("lightModel") ChatModel lightModel,
                                      PromptTemplates promptTemplates,
                                      MemoryFacade memoryFacade,
                                      ObjectProvider<MeterRegistry> meterRegistryProvider,
                                      @Value("${travel.memory.consolidation.enabled:false}") boolean enabled,
                                      @Value("${travel.memory.consolidation.batch-size:20}") int batchSize,
                                      @Value("${travel.memory.consolidation.distill.enabled:false}") boolean distillEnabled) {
        this.knowledgeClient = knowledgeClient;
        this.lightModel = lightModel;
        this.promptTemplates = promptTemplates;
        this.memoryFacade = memoryFacade;
        this.meterRegistryProvider = meterRegistryProvider;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.distillEnabled = distillEnabled;
    }

    /**
     * 写入侧挂钩（SessionKnowledgeWriter 落 chunk 时调用）；门控关=直接丢弃（E-33 零开销）。
     */
    public void recordChunk(String sessionId, String type, String seq, String content, String createdAt) {
        if (!enabled || sessionId == null || sessionId.isBlank()
                || type == null || type.isBlank() || seq == null || seq.isBlank()) {
            return;
        }
        synchronized (ledger) {
            ledger.addLast(new LedgerEntry(sessionId, type, seq, content, createdAt));
            while (ledger.size() > LEDGER_CAP) {
                ledger.pollFirst();
            }
        }
        writeLedgerRow(sessionId, type, seq, content, createdAt);
    }

    /**
     * AP-B1：台账库双写（fail-open=P0㉘——Mapper 未注入/库失败仅 WARN，内存态语义零变化）。
     * seq 超 64 截断（DDL VARCHAR(64) 防严格模式拒写）；contentHash 与 chunkId 同口径
     * （sha256 前 12 位）；createdAt 解析失败=now 兜底，双写永不上抛。
     */
    private void writeLedgerRow(String sessionId, String type, String seq, String content, String createdAt) {
        ConsolidationLedgerMapper mapper = ledgerMapper;
        if (mapper == null) {
            return;
        }
        try {
            ConsolidationLedger row = new ConsolidationLedger();
            row.setSessionId(sessionId);
            row.setChunkType(type);
            row.setSeqHash(seq.length() > 64 ? seq.substring(0, 64) : seq);
            row.setContentHash(sha256(content).substring(0, 12));
            row.setCreatedAt(parseCreatedAt(createdAt));
            mapper.insert(row);
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] 台账库双写失败（fail-open 内存态不阻断）: sessionId={}, error={}",
                    sessionId, e.getMessage());
        }
    }

    /** AP-B1：createdAt 参数解析（ISO yyyy-MM-dd'T'HH:mm:ss；null/解析失败=now 兜底） */
    private static LocalDateTime parseCreatedAt(String createdAt) {
        try {
            return LocalDateTime.parse(createdAt, ISO);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /**
     * AP-B1：启动恢复（GP-6——重启丢工作转持久化）。Mapper 在位时回填最近 LEDGER_CAP 条
     * 台账行（findRecent id 倒序取回、倒序回放=时间序入队）。恢复面=<b>会话工作清单+指纹</b>：
     * content 置空（DDL 无 content 列，实体内容以 knowledge 库为准）——空内容条目被 distill
     * 既有 blank 过滤自然排除、被 dedupGroup 恢复守卫排除（防指纹误删），会话仍进批扫
     * 工作清单（distill 可凭 facade 摘要产出真实语义）。库失败/未注入/门控关=空恢复
     * （fail-open，行为等价）。
     */
    @PostConstruct
    public void recoverLedgerFromDb() {
        ConsolidationLedgerMapper mapper = ledgerMapper;
        if (!enabled || mapper == null) {
            return;
        }
        try {
            List<ConsolidationLedger> rows = mapper.findRecent(LEDGER_CAP);
            if (rows == null || rows.isEmpty()) {
                return;
            }
            int restored = 0;
            synchronized (ledger) {
                for (int i = rows.size() - 1; i >= 0; i--) {
                    ConsolidationLedger row = rows.get(i);
                    if (row.getSessionId() == null || row.getSessionId().isBlank()) {
                        continue;
                    }
                    ledger.addLast(new LedgerEntry(row.getSessionId(), row.getChunkType(),
                            row.getSeqHash(), "", row.getCreatedAt() == null
                                    ? LocalDateTime.now().format(ISO)
                                    : row.getCreatedAt().format(ISO)));
                    restored++;
                }
                while (ledger.size() > LEDGER_CAP) {
                    ledger.pollFirst();
                }
            }
            log.info("[MemoryConsolidation] 台账启动恢复: {} 行（工作清单+指纹面，content 空=knowledge 库为准）", restored);
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] 台账启动恢复失败（fail-open 空恢复）: {}", e.getMessage());
        }
    }

    /**
     * 定时批扫：台账内去重后的会话逐个整合（单轮最多 batch-size 个会话）；门控关=no-op。
     */
    @Scheduled(cron = "${travel.memory.consolidation.cron:0 0 4 * * *}")
    public void scheduledConsolidate() {
        if (!enabled) {
            return;
        }
        List<String> sessionIds;
        synchronized (ledger) {
            sessionIds = ledger.stream()
                    .map(LedgerEntry::sessionId)
                    .distinct()
                    .limit(batchSize)
                    .toList();
        }
        long dedupDeleted = 0;
        long distillWritten = 0;
        for (String sessionId : sessionIds) {
            try {
                dedupDeleted += consolidateSession(sessionId);
            } catch (Exception e) {
                log.warn("[MemoryConsolidation] 会话整合失败（跳过）: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
            if (distillEnabled) {
                try {
                    distillWritten += distillSession(sessionId);
                } catch (Exception e) {
                    log.warn("[MemoryConsolidation] 会话蒸馏失败（跳过）: sessionId={}, error={}",
                            sessionId, e.getMessage());
                }
            }
            // AP-B1：批扫消费后台账行清理（幂等；fail-open——库失败仅 WARN，内存台账既有语义零触碰）
            try {
                if (ledgerMapper != null) {
                    ledgerMapper.deleteBySessionId(sessionId);
                }
            } catch (Exception e) {
                log.warn("[MemoryConsolidation] 台账行清理失败（fail-open）: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        }
        // RK-12/D-3：批扫收尾日志契约
        log.info("[MemoryConsolidation] sessions={} dedupDeleted={} distillWritten={}",
                sessionIds.size(), dedupDeleted, distillWritten);
    }

    /**
     * 单会话整合：台账内同会话同 type 分组 → light 判语义重复 → 旧者删除。
     *
     * @return 本会话删除条数（红线：仅删不加，绝不写入/改写内容）
     */
    public int consolidateSession(String sessionId) {
        if (!enabled || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        List<LedgerEntry> entries;
        synchronized (ledger) {
            entries = ledger.stream().filter(e -> sessionId.equals(e.sessionId())).toList();
        }
        if (entries.size() < 2) {
            return 0;
        }
        Map<String, List<Integer>> byType = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            byType.computeIfAbsent(entries.get(i).type(), k -> new ArrayList<>()).add(i);
        }
        int deleted = 0;
        for (List<Integer> group : byType.values()) {
            if (group.size() >= 2) {
                deleted += dedupGroup(sessionId, entries, group);
            }
        }
        if (deleted > 0) {
            log.info("[MemoryConsolidation] session={} dedup={}", sessionId, deleted);
            // RK-12/D-3：去重删除计数（终态快照供 lambda 捕获）
            final long deletedCount = deleted;
            registryIfPresent().ifPresent(r -> r.counter("memory.consolidation.dedup.deleted").increment(deletedCount));
        }
        return deleted;
    }

    /** 单 type 组判重与旧删；light 失败/解析失败/无重复一律零删除（fail-open）。 */
    private int dedupGroup(String sessionId, List<LedgerEntry> entries, List<Integer> group) {
        // AP-B1：恢复条目（content 空=指纹级恢复，DDL 无 content 列）不参与判重——正常流
        // content 恒非空（writeAsync 空 chunk 不入台账），本守卫仅恢复条目可触发，防指纹误删
        boolean hasRecoveredBlank = group.stream().map(entries::get)
                .anyMatch(e -> e.content() == null || e.content().isBlank());
        if (hasRecoveredBlank) {
            return 0;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < group.size(); i++) {
            sb.append("[").append(i).append("] ")
                    .append(entries.get(group.get(i)).content()).append("\n");
        }
        String raw;
        try {
            raw = lightModel.call(promptTemplates.load("memory_consolidation_dedup").formatted(sb.toString()));
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] light 判重失败，fail-open 零删除: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return 0;
        }
        int deleted = 0;
        for (List<Integer> dup : parseGroups(raw, group.size())) {
            if (dup.size() < 2) {
                continue;
            }
            // 时间戳冲突消解：组内 createdAt 旧者删除（同刻保留末位=后者）
            List<LedgerEntry> dupEntries = dup.stream().map(entries::get).toList();
            LedgerEntry keep = dupEntries.get(0);
            for (LedgerEntry e : dupEntries) {
                if (e.createdAt().compareTo(keep.createdAt()) >= 0) {
                    keep = e;
                }
            }
            for (LedgerEntry e : dupEntries) {
                if (e == keep) {
                    continue;
                }
                try {
                    knowledgeClient.deleteSessionContextByPrefix(sessionId, e.seq());
                    synchronized (ledger) {
                        ledger.remove(e);
                    }
                    deleted++;
                } catch (Exception ex) {
                    log.warn("[MemoryConsolidation] 切片删除失败（跳过）: sessionId={}, seq={}, error={}",
                            sessionId, e.seq(), ex.getMessage());
                }
            }
        }
        return deleted;
    }

    /**
     * MR-C2：情节→语义蒸馏（文章 §2.6 环节三，粒度=一会话一蒸馏 §2.3）。
     *
     * <p>会话摘要（facade getSummary）+ 台账内该会话高价值切片（≤{@value DISTILL_CHUNK_LIMIT} 条，
     * 排除既有蒸馏产物）→ light 蒸馏为 1 条语义结论 → 以 type=semantic 入会话域；
     * 实体显式命名以 type=entity 附带落库（G15）。seq 统一挂 {@value DISTILL_SEQ_PREFIX} 前缀，
     * 写入前按前缀<b>先删后增</b>——重跑幂等，不留旧版本混叠。</p>
     *
     * <p>门控关（distill.enabled=false 默认）/摘要与切片皆空/light 失败或解析失败：零副作用返回 0
     * （E-33；与 C1 的"仅删不加"红线不冲突——本方法写入的是<b>新增蒸馏产物</b>，非改写既有记忆）。</p>
     *
     * @return 1=产出一条蒸馏；0=未产出
     */
    public int distillSession(String sessionId) {
        if (!enabled || !distillEnabled || sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        String summary;
        try {
            summary = memoryFacade.getSummary(sessionId);
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] 摘要读取失败，按空处理: sessionId={}, error={}",
                    sessionId, e.getMessage());
            summary = null;
        }
        List<String> chunkTexts;
        synchronized (ledger) {
            chunkTexts = ledger.stream()
                    .filter(e -> sessionId.equals(e.sessionId()))
                    .map(LedgerEntry::content)
                    .filter(c -> c != null && !c.isBlank())
                    .limit(DISTILL_CHUNK_LIMIT)
                    .toList();
        }
        if ((summary == null || summary.isBlank()) && chunkTexts.isEmpty()) {
            return 0;
        }
        StringBuilder chunksText = new StringBuilder();
        for (String c : chunkTexts) {
            chunksText.append("- ").append(c).append("\n");
        }
        String raw;
        try {
            raw = lightModel.call(promptTemplates
                    .load("memory_consolidation_distill")
                    .formatted(summary == null ? "" : summary, chunksText.toString()));
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] 蒸馏调用失败，fail-open 零写入: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return 0;
        }
        String semantic;
        List<String> entities;
        try {
            Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(raw);
            if (!m.find()) {
                return 0;
            }
            JsonNode root = JSON.readTree(m.group());
            semantic = root.path("semantic").asText("");
            entities = new ArrayList<>();
            JsonNode arr = root.get("entities");
            if (arr != null && arr.isArray()) {
                arr.forEach(n -> {
                    String s = n.asText("");
                    if (!s.isBlank()) {
                        entities.add(s);
                    }
                });
            }
        } catch (Exception e) {
            log.warn("[MemoryConsolidation] 蒸馏解析失败，fail-open 零写入: sessionId={}, error={}",
                    sessionId, e.getMessage());
            return 0;
        }
        if (semantic.isBlank()) {
            return 0;
        }
        // 先删后增幂等：distill: 前缀定向清空旧蒸馏产物（本会话），再写入新产物
        knowledgeClient.deleteSessionContextByPrefix(sessionId, DISTILL_SEQ_PREFIX);
        String createdAt = LocalDateTime.now().format(ISO);
        knowledgeClient.writeSessionContext(chunk(sessionId, "semantic", "distill:semantic", semantic, createdAt));
        if (!entities.isEmpty()) {
            knowledgeClient.writeSessionContext(chunk(sessionId, "entity", "distill:entities",
                    "实体：" + String.join("、", entities), createdAt));
        }
        log.info("[MemoryConsolidation] session={} distill=1 entities={}", sessionId, entities.size());
        // RK-12/D-3：蒸馏写入计数
        registryIfPresent().ifPresent(r -> r.counter("memory.consolidation.distill.written").increment());
        return 1;
    }

    /** RK-12/D-3：registry provider 契约封装（可空/缺失=空 Optional，全流程零 NPE） */
    private Optional<MeterRegistry> registryIfPresent() {
        MeterRegistry registry = meterRegistryProvider != null ? meterRegistryProvider.getIfAvailable() : null;
        return registry == null ? Optional.empty() : Optional.of(registry);
    }

    /** 组装蒸馏写入体（chunkId=sessionId:type:contentHash，与 SessionKnowledgeWriter 同口径） */
    private static Map<String, Object> chunk(String sessionId, String type, String seq, String content,
                                             String createdAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("chunkId", sessionId + ":" + type + ":" + sha256(content).substring(0, 12));
        body.put("sessionId", sessionId);
        body.put("type", type);
        body.put("seq", seq);
        body.put("content", content);
        body.put("role", "assistant");
        body.put("sourceNode", "consolidation");
        body.put("createdAt", createdAt);
        return body;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * 防御解析 light 输出 {"groups": [[i,j],...]}；提取首个 JSON 对象，索引越界/格式错
     * 一律返回空列表（fail-open 零删除，E-33 关闭态/异常态不产生副作用）。
     */
    static List<List<Integer>> parseGroups(String raw, int maxIndex) {
        try {
            Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(raw);
            if (!m.find()) {
                return List.of();
            }
            JsonNode root = JSON.readTree(m.group());
            JsonNode groups = root.get("groups");
            List<List<Integer>> out = new ArrayList<>();
            if (groups == null || !groups.isArray()) {
                return List.of();
            }
            for (JsonNode arr : groups) {
                if (!arr.isArray() || arr.isEmpty()) {
                    continue;
                }
                List<Integer> idx = new ArrayList<>();
                for (JsonNode n : arr) {
                    int v = n.asInt(-1);
                    if (v < 0 || v >= maxIndex) {
                        idx = List.of();
                        break;
                    }
                    idx.add(v);
                }
                if (!idx.isEmpty()) {
                    out.add(idx);
                }
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
