package com.travel.planning.controller;

import com.travel.common.config.GrayFlags;
import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.service.ItineraryVersionPortImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * M13-2f：聊天行程回写 HTTP 桥（供 8083 WebFlux 进程调用 8081 的本地实现）。
 *
 * <p>WebFlux 试点模块不依赖 travel-planning，无法注入
 * {@link ItineraryVersionPortImpl}；本端点让中立 Port 在 MVC 侧执行建行程/REFINE 建版，
 * 8083 通过 WebClient 调用后仍保持“回写失败静默降级”语义。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/itineraries")
public class ChatItineraryWritebackController {

    private final ItineraryVersionPort itineraryVersionPort;
    private final String internalToken;

    public ChatItineraryWritebackController(ItineraryVersionPort itineraryVersionPort,
                                            @Value("${travel.internal.token:}") String internalToken) {
        this.itineraryVersionPort = itineraryVersionPort;
        this.internalToken = internalToken;
    }

    @PostMapping("/chat-writeback")
    public R<Long> chatWriteback(@RequestHeader(value = GrayFlags.HEADER_INTERNAL_TOKEN, required = false) String headerToken,
                                 @RequestBody Map<String, Object> body) {
        // M21-2（SEC-02-02）：桥端点仅接受携带共享内部令牌的进程间调用（fail-closed：
        // 服务端未配置或请求头缺失/不匹配一律拒绝），不再对任意登录用户开放、不再信任 body.userId 身份。
        if (internalToken == null || internalToken.isBlank()
                || headerToken == null || !internalToken.equals(headerToken)) {
            throw new BusinessException(40101, "内部调用凭证缺失或不匹配");
        }
        Long userId = body.get("userId") instanceof Number n ? n.longValue() : null;
        String sessionId = asString(body.get("sessionId"));
        String userInput = asString(body.get("userInput"));
        String routePlanJson = asString(body.get("routePlanJson"));
        String budgetJson = asString(body.get("budgetJson"));
        Long itineraryId = itineraryVersionPort.syncAfterPlanning(
                userId, sessionId, userInput, routePlanJson, budgetJson).orElse(null);
        log.info("[ItineraryWritebackBridge] sessionId={}, itineraryId={}", sessionId, itineraryId);
        return R.ok(itineraryId);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
