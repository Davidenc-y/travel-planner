package com.travel.planning.controller;

import com.travel.common.config.GrayFlags;
import com.travel.common.result.R;
import com.travel.common.web.support.InternalTokenSupport;
import com.travel.planning.agent.support.ItineraryVersionPort;
import com.travel.planning.service.ItineraryVersionPortImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    /** RK-16：内部令牌校验单点（原内联校验块收敛，语义逐字） */
    private final InternalTokenSupport internalTokenSupport;

    public ChatItineraryWritebackController(ItineraryVersionPort itineraryVersionPort,
                                            InternalTokenSupport internalTokenSupport) {
        this.itineraryVersionPort = itineraryVersionPort;
        this.internalTokenSupport = internalTokenSupport;
    }

    @PostMapping("/chat-writeback")
    public R<Long> chatWriteback(@RequestHeader(value = GrayFlags.HEADER_INTERNAL_TOKEN, required = false) String headerToken,
                                 @RequestBody Map<String, Object> body) {
        // M21-2（SEC-02-02）：桥端点仅接受携带共享内部令牌的进程间调用（fail-closed：
        // 服务端未配置或请求头缺失/不匹配一律拒绝），不再对任意登录用户开放、不再信任 body.userId 身份。
        internalTokenSupport.requireValid(headerToken);
        Long userId = body.get("userId") instanceof Number n ? n.longValue() : null;
        String sessionId = asString(body.get("sessionId"));
        String userInput = asString(body.get("userInput"));
        String routePlanJson = asString(body.get("routePlanJson"));
        String budgetJson = asString(body.get("budgetJson"));
        // S-D0：桥侧生成的幂等键透传（空则 planning 实现自行生成，语义不变）
        String clientRequestId = asString(body.get("clientRequestId"));
        Long itineraryId = itineraryVersionPort.syncAfterPlanning(
                userId, sessionId, userInput, routePlanJson, budgetJson, clientRequestId).orElse(null);
        log.info("[ItineraryWritebackBridge] sessionId={}, itineraryId={}", sessionId, itineraryId);
        return R.ok(itineraryId);
    }

    /**
     * S-D0（A 案）：按幂等键反查行程 id（webflux 桥"超时后查再决"通道，只读）。
     */
    @GetMapping("/chat-writeback")
    public R<Long> chatWritebackLookup(@RequestHeader(value = GrayFlags.HEADER_INTERNAL_TOKEN, required = false) String headerToken,
                                       @RequestParam(value = "clientRequestId", required = false) String clientRequestId) {
        internalTokenSupport.requireValid(headerToken);
        return R.ok(itineraryVersionPort.findItineraryIdByClientRequestId(clientRequestId).orElse(null));
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
