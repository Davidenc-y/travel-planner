package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.travel.common.entity.Itinerary;
import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * M28-6：行程标题重命名（详情页双击编辑）。
 *
 * <p>独立小服务（仅 ItineraryMapper 依赖）：归属校验 40401/40302 与详情端点同语义；
 * 新旧标题一致幂等返回（前端同值不发包，后端兜底防重放）；空/超长 40001。
 * 标题是主表元信息，不动版本链。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryRenameService {

    private static final int MAX_TITLE_LEN = 100;

    private final ItineraryMapper itineraryMapper;

    /** @return 更新后的标题（与入参一致；同值幂等不落库） */
    public String rename(Long userId, Long itineraryId, String rawTitle) {
        String title = rawTitle == null ? "" : rawTitle.trim();
        if (title.isEmpty()) {
            throw new BusinessException(40001, "标题不能为空");
        }
        if (title.length() > MAX_TITLE_LEN) {
            throw new BusinessException(40001, "标题不能超过" + MAX_TITLE_LEN + "个字符");
        }
        Itinerary entity = itineraryMapper.selectById(itineraryId);
        if (entity == null) {
            throw new BusinessException(40401, "行程不存在: " + itineraryId);
        }
        if (!userId.equals(entity.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
        if (title.equals(entity.getTitle())) {
            return title; // 同值幂等：不产生 UPDATE（前端同值已不发包，此处兜底）
        }
        itineraryMapper.update(null, new UpdateWrapper<Itinerary>()
                .eq("id", itineraryId)
                .set("title", title)
                .set("updated_at", LocalDateTime.now()));
        log.info("[ItineraryRename] 标题已更新: itineraryId={}, old={}, new={}",
                itineraryId, entity.getTitle(), title);
        return title;
    }
}
