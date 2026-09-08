package com.travel.planning.service;

import com.travel.common.entity.Itinerary;
import com.travel.common.exception.BusinessException;
import com.travel.planning.repository.ItineraryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * M28-13：用户显式修改偏好元数据（同行人/兴趣）→ 行程约束列持久化。
 *
 * <p>边界（与 §1.7"行程内容唯一编辑通道是 AI"不冲突）：仅更新偏好元数据列
 * party/interests——不更新 days/budget/startDate（避免"天数变了但 dayPlans 未
 * 重排"的内容不一致，那些仍作为下一轮约束传给 AI）；不触碰 content/mindmap。
 * 同值幂等（不发包）；归属校验同 ItineraryRenameService 语义。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryPreferenceConstraintsService {

    private final ItineraryMapper itineraryMapper;
    private final ItineraryDtoAssembler dtoAssembler;

    /** party/interests 定向更新（null=不改）；返回更新后 DTO 供详情页/标签回显。 */
    public com.travel.common.dto.ItineraryResponseDTO update(Long userId, Long id,
            String party, List<String> interests) {
        Itinerary current = itineraryMapper.selectById(id);
        if (current == null) {
            throw new BusinessException(40401, "行程不存在: " + id);
        }
        if (!userId.equals(current.getUserId())) {
            throw new BusinessException(40302, "无权访问该行程");
        }
        boolean partyChanged = party != null && !party.isBlank() && !party.equals(current.getParty());
        String interestsJson = interests == null || interests.isEmpty()
                ? null : com.travel.common.util.JsonUtils.toJson(interests);
        boolean interestsChanged = interestsJson != null && !interestsJson.equals(current.getInterests());
        if (partyChanged || interestsChanged) {
            com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary> uw =
                    new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Itinerary>()
                            .eq("id", id);
            if (partyChanged) {
                uw.set("party", party);
                current.setParty(party);
            }
            if (interestsChanged) {
                uw.set("interests", interestsJson);
                current.setInterests(interestsJson);
            }
            uw.set("updated_at", java.time.LocalDateTime.now());
            itineraryMapper.update(null, uw);
            log.info("[ItineraryConstraints] 偏好元数据已更新: itineraryId={}, party={}, interests={}",
                    id, partyChanged ? party : "(unchanged)", interestsChanged ? interestsJson : "(unchanged)");
        }
        return dtoAssembler.toResponseDTO(current, false);
    }
}
