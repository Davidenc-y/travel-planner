package com.travel.webflux.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.agent.support.ItineraryVersionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M13-2f：WebFlux(8083) 侧的 ItineraryVersionPort 实现——HTTP 桥接到
 * planning(8081) {@code /api/v1/itineraries/chat-writeback}。
 *
 * <p>RK-17/E-8：WebClient 构建段收敛至 {@link InternalBridgeClient}（头注入/序列化共享），
 * 方法体仅保留 URI/超时(15s)/解析差异；对外行为字节等价。</p>
 */
@Slf4j
@Component
public class WebfluxItineraryVersionPort implements ItineraryVersionPort {

    private final InternalBridgeClient bridgeClient;
    private final ObjectMapper objectMapper;

    public WebfluxItineraryVersionPort(InternalBridgeClient bridgeClient) {
        this.bridgeClient = bridgeClient;
        this.objectMapper = bridgeClient.objectMapper();
    }

    @Override
    public Optional<Long> syncAfterPlanning(Long userId, String sessionId,
                                            String userInput, String routePlanJson,
                                            String budgetJson, String clientRequestId) {
        if (sessionId == null || sessionId.isBlank()
                || routePlanJson == null || routePlanJson.isBlank()) {
            return Optional.empty();
        }
        // S-D0（A 案）：桥侧生成幂等键随载荷下发——planning 建行程时优先采用；
        // 超时/异常后凭此键"查再决"（uk_client_request_id 唯一，恢复不产生重复行程）。
        String idempotencyKey = clientRequestId != null && !clientRequestId.isBlank()
                ? clientRequestId : "chat-" + java.util.UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("sessionId", sessionId);
        payload.put("userInput", userInput);
        payload.put("routePlanJson", routePlanJson);
        payload.put("budgetJson", budgetJson);
        payload.put("clientRequestId", idempotencyKey);
        try {
            // RK-17/E-8：链段收敛 client（contentType/头注入/bodyValue 同链；15s 超时保留本方法体）
            String raw = bridgeClient.postJson("/api/v1/itineraries/chat-writeback", payload)
                    .block(Duration.ofSeconds(15));
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || root.path("code").asInt(0) != 200
                    || root.path("data").isNull()) {
                return Optional.empty();
            }
            long itineraryId = root.path("data").asLong();
            return itineraryId > 0 ? Optional.of(itineraryId) : Optional.empty();
        } catch (Exception e) {
            // S-D0：超时≠失败——先按幂等键查再决（CREATE 场景可恢复；REFINE 查不到则记 UNKNOWN 降级）
            Optional<Long> recovered = reconcileByIdempotencyKey(idempotencyKey);
            if (recovered.isPresent()) {
                log.info("[ItineraryWritebackBridge] 超时后查再决恢复: sessionId={}, itineraryId={}, err={}",
                        sessionId, recovered.get(), e.getMessage());
                return recovered;
            }
            log.warn("[WritebackUnknown] 回写结果未知且按幂等键未查到（不自动重试创建）: sessionId={}, key={}, err={}",
                    sessionId, idempotencyKey, e.getMessage());
            return Optional.empty();
        }
    }

    /** S-D0：按幂等键查询 planning（只读 GET，5s 短超时，失败即 empty）。 */
    private Optional<Long> reconcileByIdempotencyKey(String clientRequestId) {
        try {
            String raw = bridgeClient.getJson("/api/v1/itineraries/chat-writeback?clientRequestId="
                    + java.net.URLEncoder.encode(clientRequestId, java.nio.charset.StandardCharsets.UTF_8))
                    .block(Duration.ofSeconds(5));
            JsonNode root = objectMapper.readTree(raw);
            if (root != null && root.path("code").asInt(0) == 200 && root.path("data").isNumber()) {
                long id = root.path("data").asLong();
                return id > 0 ? Optional.of(id) : Optional.empty();
            }
        } catch (Exception ex) {
            log.warn("[ItineraryWritebackBridge] 查再决查询失败: key={}, err={}", clientRequestId, ex.getMessage());
        }
        return Optional.empty();
    }
}
