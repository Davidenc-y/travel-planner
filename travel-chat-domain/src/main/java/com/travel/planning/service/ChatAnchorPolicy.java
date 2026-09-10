package com.travel.planning.service;

import com.travel.planning.memory.anchor.SessionAnchorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * R4.2：锚定策略（自 ChatService 迁出，锚定决策可独立单测）。
 *
 * <p>自动锚定判定 + 锚定持久化薄封装；无状态组件，持久化经入参 store
 * 直接委托 {@link SessionAnchorStore}（M28-13 幂等写语义零变更）。</p>
 */
@Component
public class ChatAnchorPolicy {

    /**
     * M28-13：用户已显式携带锚定（含新会话首条消息的预选草稿）时不被新行程覆盖——
     * 未携带（null/空集）才允许会话首个生成行程自动锚定。
     */
    public boolean shouldAutoAnchor(List<Long> carriedAnchorIds) {
        return carriedAnchorIds == null || carriedAnchorIds.isEmpty();
    }

    /** 锚定持久化薄封装：直接委托 SessionAnchorStore（M28-13：用户显式选择=持续意图；与现值一致时无额外写放大）。 */
    public void persistAnchors(SessionAnchorStore store, Long userId, String sessionId, List<Long> ids) {
        store.replaceAnchors(userId, sessionId, ids);
    }
}
