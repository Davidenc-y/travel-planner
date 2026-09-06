package com.travel.webflux.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.planning.agent.support.ItineraryVersionPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * M13-2f：WebFlux(8083) 侧的 ItineraryVersionPort 实现——HTTP 桥接到
 * planning(8081) {@code /api/v1/itineraries/chat-writeback}。
 */
@Slf4j
@Component
public class WebfluxItineraryVersionPort implements ItineraryVersionPort {

    private final WebClient webClient;
    private final String internalToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public WebfluxItineraryVersionPort(
            @Value("${travel.planning-base-url:http://localhost:8081}") String planningBaseUrl,
            @Value("${travel.internal.token:}") String internalToken) {
        this.webClient = WebClient.builder().baseUrl(planningBaseUrl).build();
        this.internalToken = internalToken;
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
            String raw = webClient.post()
                    .uri("/api/v1/itineraries/chat-writeback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        // M21-2（SEC-02-02）：进程间共享内部令牌（与 planning 侧 travel.internal.token 同源）
                        if (internalToken != null && !internalToken.isBlank()) {
                            h.set("X-Internal-Token", internalToken);
                        }
                    })
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
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
