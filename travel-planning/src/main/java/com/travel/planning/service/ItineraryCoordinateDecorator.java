package com.travel.planning.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.AttractionVisit;
import com.travel.common.dto.ItineraryResponseDTO.DayPlan;
import com.travel.common.entity.Attraction;
import com.travel.planning.agent.support.AttractionGroundingChecker;
import com.travel.planning.repository.AttractionCoordinateLookupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M11-2：行程 DTO 坐标装饰器。
 *
 * <p>读取时按目的地城市一次性回查 t_attraction（lat/lng 非空），再用
 * {@link AttractionGroundingChecker#matches} 名称归一匹配行程景点，
 * 为每个可匹配的 AttractionVisit 填充 latitude/longitude；缺失坐标保持 null，
 * 前端地图可降级为“无坐标景点”列表。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryCoordinateDecorator {

    private final AttractionCoordinateLookupMapper attractionCoordinateLookupMapper;

    /** 若 dayPlans 为空或目的地为空则原样返回。 */
    public void decorate(ItineraryResponseDTO dto) {
        if (dto == null || dto.getDestination() == null || dto.getDestination().isBlank()
                || dto.getDayPlans() == null || dto.getDayPlans().isEmpty()) {
            return;
        }
        List<String> routeNames = new ArrayList<>();
        for (DayPlan day : dto.getDayPlans()) {
            if (day.getAttractions() == null) {
                continue;
            }
            for (AttractionVisit v : day.getAttractions()) {
                if (v.getName() != null && !v.getName().isBlank()) {
                    routeNames.add(v.getName());
                }
            }
        }
        if (routeNames.isEmpty()) {
            return;
        }
        try {
            List<Attraction> candidates = attractionCoordinateLookupMapper.selectList(
                    new QueryWrapper<Attraction>()
                            .eq("city", dto.getDestination())
                            .isNotNull("lat")
                            .isNotNull("lng"));
            Map<String, Attraction> byName = new LinkedHashMap<>();
            for (Attraction a : candidates) {
                if (a.getName() != null) {
                    byName.putIfAbsent(a.getName(), a);
                }
            }
            int filled = 0;
            for (DayPlan day : dto.getDayPlans()) {
                if (day.getAttractions() == null) {
                    continue;
                }
                for (AttractionVisit v : day.getAttractions()) {
                    Attraction match = find(byName, v.getName());
                    if (match != null && match.getLat() != null && match.getLng() != null) {
                        v.setLatitude(match.getLat());
                        v.setLongitude(match.getLng());
                        v.setType(match.getType());
                        filled++;
                    }
                }
            }
            log.info("[ItineraryCoordinate] 坐标填充完成: itinerary 目的地={}, 命中={}/{}",
                    dto.getDestination(), filled, routeNames.size());
        } catch (Exception e) {
            log.warn("[ItineraryCoordinate] 坐标回查失败（地图降级）: {}",
                    e.getMessage());
        }
    }

    private static Attraction find(Map<String, Attraction> byName, String routeName) {
        if (routeName == null) {
            return null;
        }
        return byName.values().stream()
                .filter(a -> AttractionGroundingChecker.matches(a.getName(), routeName))
                .findFirst()
                .orElse(null);
    }
}
