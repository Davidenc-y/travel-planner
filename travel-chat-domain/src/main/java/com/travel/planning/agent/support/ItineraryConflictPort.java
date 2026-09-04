package com.travel.planning.agent.support;

import java.util.List;
import java.util.Map;

/**
 * M9-4：行程冲突校验端口（chat-domain 定义，travel-planning 实现注入）。
 *
 * <p>为避免 chat-domain 反向依赖 travel-planning，本接口保持中立；
 * 规划模块经 {@code ItineraryConflictPortImpl} 实现并注入 Spring 容器。
 * 校验为观测级：只写 trace，不阻断、不重试。</p>
 */
public interface ItineraryConflictPort {

    /**
     * @param routePlanJson   行程 JSON（含 days[]）
     * @param candidatesJson  候选景点 JSON 数组
     * @return 违规列表（键与 planning 侧 Violation 对齐：
     *         rule/severity/day/attraction/message）；空列表=无冲突或输入不可解析
     */
    List<Map<String, String>> validate(String routePlanJson, String candidatesJson);
}
