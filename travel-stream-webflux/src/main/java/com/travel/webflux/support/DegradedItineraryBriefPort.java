package com.travel.webflux.support;

import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * M25 补缺：webflux 试点传输的 {@link ItineraryBriefPort} 降级实现。
 *
 * <p>背景：M23 的 {@code SessionAnchorStore}（chat-domain）注入 ItineraryBriefPort，
 * 其唯一实现在 travel-planning（需读 t_itinerary）；webflux 不依赖 planning 模块，
 * 导致锚定装配链断裂、8083 无法启动（本次缺陷）。</p>
 *
 * <p>降级语义（对齐 M17-2 TripFactsPort"webflux 无实现自动降级"先例）：
 * briefOf 恒 empty（【锚定行程】段不注入、锚定建议卡片不弹出）；
 * findSessionItineraryIds 恒 empty。即 webflux 灰度传输下锚定相关上下文降级，
 * 聊天主链路（守卫/记忆/路由/流式）不受影响。</p>
 *
 * <p>升级路径：后续为 planning 增加内部 brief 查询端点后，改造为
 * WebClient 桥接实现（对齐 WebfluxItineraryVersionPort 模式），随 D-Q3
 * 行程 SSE 迁移批次一并处理。</p>
 */
@Slf4j
@Component
public class DegradedItineraryBriefPort implements ItineraryBriefPort {

    public DegradedItineraryBriefPort() {
        log.warn("[ItineraryBriefPort] webflux 降级实现生效：锚定行程 brief 不可用（gray 试点传输）");
    }

    @Override
    public Optional<ItineraryBrief> briefOf(Long userId, Long itineraryId) {
        return Optional.empty();
    }

    @Override
    public List<Long> findSessionItineraryIds(String sessionId) {
        return List.of();
    }
}
