package com.travel.planning.controller;

import com.travel.common.exception.BusinessException;
import com.travel.common.result.R;
import com.travel.planning.memory.anchor.ItineraryBrief;
import com.travel.planning.memory.anchor.ItineraryBriefPort;
import com.travel.planning.memory.anchor.SessionAnchorStore;
import com.travel.planning.memory.sessionstore.SessionStorePort;
import com.travel.planning.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * M23（E1）：会话锚定 REST（GET 回读恢复 / PUT 全量替换）。
 *
 * <p>边界约束（契约锁定）：本控制器<b>只有</b>读取与锚定集合替换两个操作，
 * 无任何行程内容编辑语义——"用户不能直接编辑锚定行程"由架构保证
 * （行程内容变更唯一通道是 AI REFINE 生成新版本，见 ItineraryVersionPort）。</p>
 *
 * <p>归属：userId 由 JWT 决定（M16-1 单源）+ 会话归属断言；PUT 对每个
 * requested id 经 ItineraryBriefPort 校验（非本人/不存在自动剔除=自愈）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat/sessions/{sessionId}/anchored-itineraries")
@RequiredArgsConstructor
public class SessionAnchorController {

    private final SessionAnchorStore sessionAnchorStore;
    private final ItineraryBriefPort itineraryBriefPort;
    private final SessionStorePort sessionStorePort;

    /** 回读锚定集合（含 brief，前端标签行直接渲染；已删除行程顺带自愈剔除）。 */
    @GetMapping
    public R<List<ItineraryBrief>> getAnchors(@PathVariable String sessionId) {
        Long userId = AuthUtils.resolveUserId();
        requireOwnedSession(userId, sessionId);
        List<Long> stored = sessionAnchorStore.getAnchors(sessionId);
        List<Long> valid = sessionAnchorStore.replaceAnchors(userId, sessionId, stored);
        return R.ok(rebuildBriefs(userId, valid));
    }

    /** 全量替换锚定集合（幂等；返回替换后的有效集合供前端校准）。 */
    @PutMapping
    public R<List<Long>> replaceAnchors(@PathVariable String sessionId,
                                        @RequestBody AnchorReplaceRequest body) {
        Long userId = AuthUtils.resolveUserId();
        requireOwnedSession(userId, sessionId);
        List<Long> effective = sessionAnchorStore.replaceAnchors(userId, sessionId, body.itineraryIds());
        log.info("[SessionAnchor] replaced: sessionId={}, effective={}", sessionId, effective.size());
        return R.ok(effective);
    }

    private void requireOwnedSession(Long userId, String sessionId) {
        var session = sessionStorePort.findBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(40404, "会话不存在");
        }
        if (!userId.equals(session.getUserId())) {
            throw new BusinessException(40302, "无权访问该会话");
        }
    }

    private List<ItineraryBrief> rebuildBriefs(Long userId, List<Long> ids) {
        List<ItineraryBrief> briefs = new ArrayList<>();
        for (Long id : ids) {
            itineraryBriefPort.briefOf(userId, id).ifPresent(briefs::add);
        }
        return briefs;
    }

    /** PUT 请求体（itineraryIds 全量替换语义；null=清空）。 */
    public record AnchorReplaceRequest(List<Long> itineraryIds) {
    }
}
