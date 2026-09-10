package com.travel.planning.service;

import org.springframework.stereotype.Component;

/**
 * R4.1：偏好观测日志摘要支持（preferenceSummary 方法体自 ChatService 原样迁出，
 * 供 [ChatPreference] 本轮偏好标签观测日志装配摘要文本，文案与字段顺序零变更）。
 */
@Component
public class ChatPreferenceLogSupport {

    /** M28-14：偏好标签非空字段摘要（观测日志用；null/全空返回"(未携带)"）。 */
    public String preferenceSummary(com.travel.common.dto.PreferenceTagsDTO p) {
        if (p == null) {
            return "(未携带)";
        }
        StringBuilder sb = new StringBuilder();
        if (p.getDestination() != null) sb.append("destination=").append(p.getDestination()).append(',');
        if (p.getDays() != null) sb.append("days=").append(p.getDays()).append(',');
        if (p.getBudget() != null) sb.append("budget=").append(p.getBudget().toPlainString()).append(',');
        if (p.getParty() != null) sb.append("party=").append(p.getParty()).append(',');
        if (p.getInterests() != null && !p.getInterests().isEmpty()) sb.append("interests=").append(p.getInterests()).append(',');
        if (p.getStartDate() != null) sb.append("startDate=").append(p.getStartDate()).append(',');
        if (Boolean.TRUE.equals(p.getRemember())) sb.append("remember=true").append(',');
        return sb.isEmpty() ? "(空标签)" : sb.substring(0, sb.length() - 1);
    }
}
