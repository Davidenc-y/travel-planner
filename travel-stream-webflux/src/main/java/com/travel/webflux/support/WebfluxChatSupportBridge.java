package com.travel.webflux.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.config.GrayFlags;
import com.travel.planning.agent.support.ChatWeatherContextPort;
import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M26-F2：webflux 灰度传输的 {@link ItineraryBriefPort} / {@link ChatWeatherContextPort}
 * <b>HTTP 桥实现</b>——经内部端点（X-Internal-Token fail-closed）复用 planning 单源逻辑，
 * 取代 M25 的恒空降级实现（该降级曾导致 webflux 下：锚定建议卡不出现、【锚定行程】段不注入、
 * 天气参考段缺失）。任一调用失败按降级语义返回空（不阻断聊天主链路），并记录 DEBUG。
 *
 * <p>桥接端点（planning 8081，InternalChatSupportController）：
 * GET /api/v1/itineraries/chat-brief、GET .../chat-session-itineraries、POST .../chat-weather。</p>
 */
@Slf4j
@Component
public class WebfluxChatSupportBridge implements ItineraryBriefPort, ChatWeatherContextPort {

    private final WebClient webClient;
    private final String internalToken;
    private final ObjectMapper mapper = new ObjectMapper();

    public WebfluxChatSupportBridge(
            @Value("${travel.planning-base-url:http://localhost:8081}") String planningBaseUrl,
            @Value("${travel.internal.token:}") String internalToken) {
        this.webClient = WebClient.builder().baseUrl(planningBaseUrl).build();
        this.internalToken = internalToken;
    }

    // ---------------- ItineraryBriefPort ----------------

    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        if (userId == null || itineraryId == null) {
            return Optional.empty();
        }
        try {
            String raw = webClient.get()
                    .uri(uri -> uri.path("/api/v1/itineraries/chat-brief")
                            .queryParam("itineraryId", itineraryId)
                            .queryParam("userId", userId)
                            .build())
                    .headers(h -> auth(h))
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            JsonNode data = mapper.readTree(raw).path("data");
            if (data == null || data.isNull() || data.isMissingNode()) {
                return Optional.empty();
            }
            java.util.ArrayList<String> attractions = new java.util.ArrayList<>();
            data.path("attractionNames").forEach(n -> {
                String name = n.asText(null);
                if (name != null && !name.isBlank()) {
                    attractions.add(name);
                }
            });
            // M28-12：interests（chat-brief 载荷新增字段；旧 planning 未返回时为空列表）
            java.util.ArrayList<String> interests = new java.util.ArrayList<>();
            data.path("interests").forEach(n -> {
                String i = n.asText(null);
                if (i != null && !i.isBlank()) {
                    interests.add(i);
                }
            });
            return Optional.of(new ItineraryBrief(
                    data.path("id").asLong(),
                    data.path("title").asText(null),
                    data.path("destination").asText(null),
                    data.path("days").isNumber() ? data.path("days").asInt() : null,
                    data.path("startDate").isTextual() ? data.path("startDate").asText(null) : null,
                    data.path("budget").isTextual() ? data.path("budget").asText(null) : null,
                    data.path("party").isTextual() ? data.path("party").asText(null) : null,
                    interests,
                    data.path("version").isNumber() ? data.path("version").asInt() : null,
                    attractions));
        } catch (Exception e) {
            log.debug("[ChatSupportBridge] brief 桥失败（降级空）: id={}, {}", itineraryId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public List<Long> findSessionItineraryIds(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            String raw = webClient.get()
                    .uri(uri -> uri.path("/api/v1/itineraries/chat-session-itineraries")
                            .queryParam("sessionId", sessionId).build())
                    .headers(this::auth)
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));
            JsonNode data = mapper.readTree(raw).path("data");
            if (!data.isArray()) {
                return List.of();
            }
            java.util.ArrayList<Long> ids = new java.util.ArrayList<>();
            data.forEach(n -> {
                if (n.isNumber()) {
                    ids.add(n.asLong());
                }
            });
            return ids;
        } catch (Exception e) {
            log.debug("[ChatSupportBridge] session-itineraries 桥失败（降级空）: {}", e.getMessage());
            return List.of();
        }
    }

    // ---------------- ChatWeatherContextPort ----------------

    @Override
    public String build(String composed) {
        if (composed == null || composed.isBlank()) {
            return "";
        }
        try {
            String raw = webClient.post()
                    .uri("/api/v1/itineraries/chat-weather")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::auth)
                    .bodyValue(Map.of("composed", composed))
                    .retrieve().bodyToMono(String.class)
                    .block(Duration.ofSeconds(8));
            JsonNode data = mapper.readTree(raw).path("data");
            return data.isTextual() ? data.asText("") : "";
        } catch (Exception e) {
            log.debug("[ChatSupportBridge] weather 桥失败（降级空）: {}", e.getMessage());
            return "";
        }
    }

    private void auth(org.springframework.http.HttpHeaders h) {
        if (internalToken != null && !internalToken.isBlank()) {
            h.set(GrayFlags.HEADER_INTERNAL_TOKEN, internalToken);
        }
    }
}
