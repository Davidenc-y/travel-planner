package com.travel.planning.memory.knowledge;

import com.travel.planning.client.KnowledgeClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 会话知识写入/检索（Phase C/F78，C1 提交 + C3 注入）。
 *
 * <p>写入异步（不阻塞对话/行程），失败仅 WARN；chunkId=sessionId:type:contentHash 幂等
 * （knowledge 侧先删后插 + ES docId 覆盖）。检索失败降级空串不影响主流程。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionKnowledgeWriter {

    private static final ExecutorService WRITE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** M4-5b：二次取父单次取回上限（覆盖超长多日行程；knowledge 侧另有夹逼） */
    private static final int BY_PREFIX_LIMIT = 30;

    private final KnowledgeClient knowledgeClient;

    /**
     * 异步写入一批切片（空集合跳过）。
     */
    public void writeAsync(String sessionId, List<SessionChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (SessionChunk chunk : chunks) {
            if (chunk.content() == null || chunk.content().isBlank()) {
                continue;
            }
            String hash = sha256(chunk.content()).substring(0, 12);
            String chunkId = sessionId + ":" + chunk.type() + ":" + hash;
            String seq = chunk.seq() != null ? chunk.seq() : hash.substring(0, 8);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chunkId", chunkId);
            body.put("sessionId", sessionId);
            body.put("type", chunk.type());
            body.put("seq", seq);
            body.put("content", chunk.content());
            body.put("role", chunk.role());
            body.put("sourceNode", chunk.sourceNode());
            body.put("createdAt", LocalDateTime.now().format(ISO));
            CompletableFuture.runAsync(() -> {
                try {
                    knowledgeClient.writeSessionContext(body);
                } catch (Exception e) {
                    log.warn("[SessionKnowledge] 切片写入失败（不影响主流程）: chunkId={}, error={}",
                            chunkId, e.getMessage());
                }
            }, WRITE_EXECUTOR);
        }
    }

    /**
     * 检索会话知识并组装为注入文本（"【type】content" 行）；失败/空返回空串。
     */
    public String search(String sessionId, String query, int topK) {
        return format(retrieve(sessionId, query, topK));
    }

    /**
     * F85：检索会话知识并返回结构化切片（供会话事实共识层消费）；
     * 与 {@link #search} 共用 {@link #retrieve}，同一请求只检索一次。
     */
    public List<Map<String, Object>> searchStructured(String sessionId, String query, int topK) {
        return retrieve(sessionId, query, topK);
    }

    /** F85：把结构化切片渲染为注入文本（"【type】content" 行）；空列表返回空串。 */
    public static String format(List<Map<String, Object>> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> hit : hits) {
            sb.append("【").append(hit.getOrDefault("type", "unknown")).append("】")
                    .append(hit.getOrDefault("content", "")).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * M4-5b：按 seq 前缀取回会话切片（二次取父）。
     *
     * <p>用于 itinerary_day 命中被 topK 截断丢天的场景：以 {@code "itin:{id}:"} 为前缀
     * 取回该行程<b>全部</b>天块，拼出完整父视图。失败/空返回降级空列表（调用方保留原命中，
     * 回归零风险）。取父数据来自 ES 原文（content 完整、未做 300 字截断）。</p>
     */
    public List<Map<String, Object>> fetchBySeqPrefix(String sessionId, String seqPrefix) {
        if (sessionId == null || sessionId.isBlank() || seqPrefix == null || seqPrefix.isBlank()) {
            return List.of();
        }
        try {
            var resp = knowledgeClient.findSessionContextByPrefix(sessionId, seqPrefix, BY_PREFIX_LIMIT);
            if (resp == null || resp.getData() == null) {
                return List.of();
            }
            return resp.getData();
        } catch (Exception e) {
            log.warn("[SessionKnowledge] 按前缀取父失败，降级保留原命中: seqPrefix={}, error={}",
                    seqPrefix, e.getMessage());
            return List.of();
        }
    }

    /** F85：检索 + F83 最近一次行程过滤，返回结构化切片（不渲染文本）。 */
    private List<Map<String, Object>> retrieve(String sessionId, String query, int topK) {
        if (sessionId == null || sessionId.isBlank() || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            var resp = knowledgeClient.searchSessionContext(sessionId, query, Math.max(1, Math.min(topK, 10)));
            if (resp == null || resp.getData() == null || resp.getData().isEmpty()) {
                return List.of();
            }
            // F83：多套行程切片混叠会让"上次行程"歧义——itinerary_day 只保留 seq 前缀
            // "itin:{id}:" 中 id 最大（最近一次）的那套；其余类型原样保留。
            String latestItinId = null;
            for (Map<String, Object> hit : resp.getData()) {
                if ("itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")))) {
                    String planId = planIdOf(hit);
                    if (planId != null && (latestItinId == null || planId.compareTo(latestItinId) > 0)) {
                        latestItinId = planId;
                    }
                }
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> hit : resp.getData()) {
                if ("itinerary_day".equals(String.valueOf(hit.getOrDefault("type", "")))
                        && latestItinId != null && !latestItinId.equals(planIdOf(hit))) {
                    continue; // 只保留最近一次行程
                }
                out.add(hit);
            }
            return out;
        } catch (Exception e) {
            log.warn("[SessionKnowledge] 会话知识检索失败，降级空注入: {}", e.getMessage());
            return List.of();
        }
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
     * F83：从 seq（itin:{id}:{day}）解析行程计划 id。
     * M4-5b：改 public 供 ChatBudgetStep 二次取父（提取命中行程 id）复用。
     */
    public static String planIdOf(Map<String, Object> hit) {
        String seq = String.valueOf(hit.getOrDefault("seq", ""));
        int i = seq.indexOf("itin:");
        if (i < 0) {
            return null;
        }
        String rest = seq.substring(i + "itin:".length());
        int c = rest.indexOf(':');
        return c > 0 ? rest.substring(0, c) : rest;
    }
}
