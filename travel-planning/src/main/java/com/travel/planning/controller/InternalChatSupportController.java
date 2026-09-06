package com.travel.planning.controller;

import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.agent.support.ChatWeatherContextPort;
import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import com.travel.planning.service.ItineraryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * M26-F2：webflux(8083) 灰度传输的内部桥端点（X-Internal-Token fail-closed，
 * 与 chat-writeback 同凭证模式）。供 webflux 侧 WebClient 端口调用：
 *
 * <ul>
 *   <li>GET  /api/v1/itineraries/chat-brief?itineraryId=&amp;userId= —— 锚定 brief
 *       （归属校验；修复 webflux 下锚定建议卡与【锚定行程】段注入）</li>
 *   <li>GET  /api/v1/itineraries/chat-session-itineraries?sessionId= —— 会话关联行程 id
 *       （suggestion 资格判定）</li>
 *   <li>POST /api/v1/itineraries/chat-weather —— composed 上下文 → 天气参考段
 *       （enabled/配额/缓存逻辑全部留在 planning 单源）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/itineraries")
public class InternalChatSupportController {

    private final ItineraryService itineraryService;
    private final ItineraryBriefPort itineraryBriefPort;
    private final ChatWeatherContextPort chatWeatherContextPort;
    private final String internalToken;

    public InternalChatSupportController(ItineraryService itineraryService,
                                         ItineraryBriefPort itineraryBriefPort,
                                         ChatWeatherContextPort chatWeatherContextPort,
                                         @Value("${travel.internal.token:}") String internalToken) {
        this.itineraryService = itineraryService;
        this.itineraryBriefPort = itineraryBriefPort;
        this.chatWeatherContextPort = chatWeatherContextPort;
        this.internalToken = internalToken;
    }

    private void requireInternalToken(String headerToken) {
        if (internalToken == null || internalToken.isBlank()
                || headerToken == null || !internalToken.equals(headerToken)) {
            throw new BusinessException(40101, "内部调用凭证缺失或不匹配");
        }
    }

    /**
     * 锚定 brief——委托 {@link ItineraryBriefPort} 单源实现（归属校验自愈 +
     * budget/party/version/景点名单全字段，供 preferenceSync 与【锚定行程】段）。
     * 非本人/不存在 → 空数据。
     */
    @GetMapping("/chat-brief")
    public R<ItineraryBrief> brief(@RequestParam Long itineraryId,
                                   @RequestParam Long userId,
                                   @RequestHeader(value = "X-Internal-Token", required = false) String headerToken) {
        requireInternalToken(headerToken);
        return R.ok(itineraryBriefPort.briefOf(userId, itineraryId).orElse(null));
    }

    /** 会话关联行程 id（suggestion 资格：无关联才询问）。 */
    @GetMapping("/chat-session-itineraries")
    public R<List<Long>> sessionItineraries(@RequestParam String sessionId,
                                            @RequestHeader(value = "X-Internal-Token", required = false) String headerToken) {
        requireInternalToken(headerToken);
        return R.ok(itineraryService.findSessionItineraryIds(sessionId));
    }

    /** composed → 天气参考段（所有开关/配额/缓存逻辑留在 planning 单源实现内）。 */
    @PostMapping("/chat-weather")
    public R<String> weather(@RequestBody Map<String, String> body,
                             @RequestHeader(value = "X-Internal-Token", required = false) String headerToken) {
        requireInternalToken(headerToken);
        return R.ok(chatWeatherContextPort.build(body.get("composed")));
    }
}
