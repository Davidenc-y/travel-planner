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
 *
 * <p><b>B2.4 现状标注（2026-09-11）</b>：brief 半边已由 Redis 旁路承接（B2.1/B2.2），
 * {@link #briefOf} 仅作回退（已 @Deprecated + [BriefBridge] 观测日志）；
 * {@link #findSessionItineraryIds} 仍是 RedisItineraryBriefPort 的 id→session 映射来源
 * （活跃元数据通道，不标注）；天气（{@link #build}）尚无旁路、本桥仍是唯一实现
 * （不标注，天气旁路化登记为候选批次）。整个桥的退役以：天气旁路落地 + [BriefBridge]
 * 日志趋零 + chat-session-itineraries 元数据通道替代方案三者齐备为前置。</p>
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

    /**
     * @deprecated B2.4 观察期（2026-09-11 起）：brief 读取已由 RedisItineraryBriefPort（@Primary）
     *         承接，本方法仅为回退通道（冷启动 miss / TTL 过期 / Redis 故障自愈——经本方法走
     *         8081 端点时会顺带补写 Redis 快照形成自愈闭环）。每次回退打 [BriefBridge] INFO 一行，
     *         作为观察期内桥使用频次的量化依据；删除决策以该日志趋零为前置。
     */
    @Deprecated
    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        if (userId == null || itineraryId == null) {
            return Optional.empty();
        }
        log.info("[BriefBridge] brief 回退经 HTTP 桥: id={}, userId={}", itineraryId, userId);
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
