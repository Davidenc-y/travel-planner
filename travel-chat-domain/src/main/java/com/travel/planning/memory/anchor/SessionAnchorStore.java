package com.travel.planning.memory.anchor;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.common.entity.ChatSession;
import com.travel.planning.prompt.Markers;
import com.travel.planning.repository.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M23（E1）：会话锚定集合存储与【锚定行程】段渲染。
 *
 * <p>存储：{@code t_chat_session.anchored_itinerary_ids}（JSON 数组文本）——
 * 用途仅为恢复（刷新/跨端标签行回读）与"可选规划为空"的服务端判定；
 * <b>消息处理时的注意力真相永远来自消息内快照</b>（per-turn truth，见 ChatMessageRequest.anchoredItineraryIds）。</p>
 *
 * <p>brief 渲染：attractions 名单截断 {@code travel.chat.anchor.brief-max-attractions=12}；
 * 已删除行程在渲染时跳过（锚定自愈，§1.7）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionAnchorStore {

    private static final TypeReference<List<Long>> IDS_TYPE = new TypeReference<>() {
    };

    private final ChatSessionMapper chatSessionMapper;
    private final ItineraryBriefPort itineraryBriefPort;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${travel.chat.anchor.brief-max-attractions:12}")
    private int briefMaxAttractions;

    /** 读取锚定集合（列空/JSON 非法 → 空列表，不抛错）。 */
    public List<Long> getAnchors(String sessionId) {
        ChatSession session = chatSessionMapper.selectOne(
                new QueryWrapper<ChatSession>().eq("session_id", sessionId));
        if (session == null || session.getAnchoredItineraryIds() == null
                || session.getAnchoredItineraryIds().isBlank()) {
            return List.of();
        }
        try {
            List<Long> ids = objectMapper.readValue(session.getAnchoredItineraryIds(), IDS_TYPE);
            return ids == null ? List.of() : ids;
        } catch (Exception e) {
            log.warn("[Anchor] 锚定集合解析失败（按空处理）: sessionId={}", sessionId);
            return List.of();
        }
    }

    /**
     * 全量替换锚定集合（PUT 语义幂等）；逐项经 briefOf 校验归属，
     * 非本人/不存在的行程自动剔除（自愈）；返回实际生效集合。
     */
    public List<Long> replaceAnchors(Long userId, String sessionId, List<Long> requested) {
        List<Long> valid = new ArrayList<>();
        if (requested != null) {
            for (Long id : requested) {
                if (id != null && itineraryBriefPort.briefOf(userId, id).isPresent() && !valid.contains(id)) {
                    valid.add(id);
                }
            }
        }
        String json;
        try {
            json = valid.isEmpty() ? null : objectMapper.writeValueAsString(valid);
        } catch (Exception e) {
            log.warn("[Anchor] 锚定集合序列化失败（按清空处理）: {}", e.getMessage());
            json = null;
        }
        int updated = chatSessionMapper.update(null, new UpdateWrapper<ChatSession>()
                .eq("session_id", sessionId)
                .set("anchored_itinerary_ids", json));
        if (updated == 0) {
            log.warn("[Anchor] 锚定写入 0 行（会话不存在？）: sessionId={}", sessionId);
        }
        return valid;
    }

    /** 渲染【锚定行程】段（无锚定或全部自愈剔除 → 空串=不注入）。 */
    public String renderSection(Long userId, List<Long> anchorIds) {
        if (anchorIds == null || anchorIds.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Markers.ANCHORED_ITINERARIES).append('\n');
        boolean any = false;
        for (Long id : anchorIds) {
            var brief = itineraryBriefPort.briefOf(userId, id);
            if (brief.isEmpty()) {
                continue;
            }
            var b = brief.get();
            if (any) {
                sb.append('\n');
            }
            sb.append("- 行程#").append(b.id()).append("《").append(b.title()).append("》");
            if (b.destination() != null) {
                sb.append(" 目的地:").append(b.destination());
            }
            if (b.days() != null) {
                sb.append(" 天数:").append(b.days());
            }
            if (b.budget() != null) {
                sb.append(" 预算:").append(b.budget());
            }
            if (b.party() != null) {
                sb.append(" 同行:").append(b.party());
            }
            sb.append(" 当前版本:v").append(b.version() == null ? 1 : b.version());
            if (b.attractionNames() != null && !b.attractionNames().isEmpty()) {
                List<String> names = b.attractionNames().size() > briefMaxAttractions
                        ? b.attractionNames().subList(0, briefMaxAttractions)
                        : b.attractionNames();
                sb.append(" 景点:").append(String.join("、", names));
            }
            any = true;
        }
        return any ? sb.toString() : "";
    }
}
