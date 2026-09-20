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
                                            String budgetJson) {
        if (sessionId == null || sessionId.isBlank()
                || routePlanJson == null || routePlanJson.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("sessionId", sessionId);
        payload.put("userInput", userInput);
        payload.put("routePlanJson", routePlanJson);
        payload.put("budgetJson", budgetJson);
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
            log.warn("[ItineraryWritebackBridge] 调用 planning 失败（静默降级）: sessionId={}, err={}",
                    sessionId, e.getMessage());
            return Optional.empty();
        }
    }
}
