package com.travel.planning.service;

import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.*;
import com.travel.common.util.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 思维导图生成服务
 *
 * <p>将行程数据转换为思维导图 JSON 结构，供前端 markmap 渲染。</p>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
public class MindmapGenerator {

    /**
     * 从行程内容 JSON 生成思维导图
     *
     * @param title        行程标题
     * @param destination  目的地
     * @param days         天数
     * @param budget       预算
     * @param contentJson  行程内容 JSON（含 dayPlans）
     * @return MindmapData
     */
    public MindmapData generate(String title, String destination, Integer days,
                                 String budget, String contentJson) {
        log.info("生成思维导图: title={}, destination={}", title, destination);

        List<Section> sections = new ArrayList<>();

        // Section 1: 行程安排
        List<String> dayItems = new ArrayList<>();
        dayItems.add("目的地：" + destination);
        dayItems.add("天数：" + days + " 天");
        if (budget != null) {
            dayItems.add("预算：" + budget + " 元");
        }
        // 尝试从 contentJson 解析每日摘要
        try {
            Map<String, Object> content = JsonUtils.fromJson(contentJson, Map.class);
            if (content != null && content.containsKey("days")) {
                List<?> dayPlans = (List<?>) content.get("days");
                for (int i = 0; i < dayPlans.size(); i++) {
                    Map<String, Object> day = (Map<String, Object>) dayPlans.get(i);
                    String summary = (String) day.getOrDefault("summary", "第" + (i + 1) + "天");
                    dayItems.add("第" + (i + 1) + "天：" + summary);
                }
            }
        } catch (Exception e) {
            dayItems.add("（行程详情解析失败）");
        }
        sections.add(Section.builder().title("行程安排").items(dayItems).build());

        // Section 2: 预算规划
        List<String> budgetItems = new ArrayList<>();
        try {
            Map<String, Object> content = JsonUtils.fromJson(contentJson, Map.class);
            if (content != null && content.containsKey("budgetEstimate")) {
                Map<String, Object> budgetData = (Map<String, Object>) content.get("budgetEstimate");
                budgetItems.add("门票：" + budgetData.getOrDefault("ticketCost", "未估算") + " 元");
                budgetItems.add("餐饮：" + budgetData.getOrDefault("mealCost", "未估算") + " 元");
                budgetItems.add("交通：" + budgetData.getOrDefault("transportCost", "未估算") + " 元");
                budgetItems.add("住宿：" + budgetData.getOrDefault("hotelCost", "未估算") + " 元");
                budgetItems.add("总计：" + budgetData.getOrDefault("totalCost", "未估算") + " 元");
            } else {
                budgetItems.add("（预算数据未生成）");
            }
        } catch (Exception e) {
            budgetItems.add("（预算解析失败）");
        }
        sections.add(Section.builder().title("预算规划").items(budgetItems).build());

        // Section 3: 注意事项
        sections.add(Section.builder()
                .title("注意事项")
                .items(List.of("提前查看天气预报", "携带必备证件", "记录紧急联系人", "注意人身安全"))
                .build());

        return MindmapData.builder()
                .title(title != null ? title : "旅行规划")
                .destination(destination)
                .days(days != null ? days.toString() : "")
                .budget(budget != null ? budget.toString() : "")
                .sections(sections)
                .build();
    }
}
