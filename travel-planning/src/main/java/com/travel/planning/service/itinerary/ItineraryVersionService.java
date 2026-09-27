package com.travel.planning.service.itinerary;

import com.travel.planning.service.itinerary.ItineraryVersionServiceImpl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ItineraryVersionService 公共契约（AD-2d 接口化）。
 *
 * <p>实现见 {@link ItineraryVersionServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface ItineraryVersionService {

    /** 定稿版本登记（四参便捷形态，委托七参）。 */
    void recordFinalized(Long itineraryId, String content,
                         String mindmapData, BigDecimal estimatedCost);

    /** 定稿版本登记（days/budget/startDate 全量形态）。 */
    void recordFinalized(Long itineraryId, String content,
                         String mindmapData, BigDecimal estimatedCost,
                         Integer days, BigDecimal budget, String startDate);

    /** 版本列表。 */
    List<Map<String, Object>> list(Long itineraryId, Long userId);

    /** 版本详情。 */
    Map<String, Object> detail(Long itineraryId, int version, Long userId);

    /** 切换当前版本。 */
    Integer switchTo(Long userId, Long itineraryId, Integer targetVersion);

    /** 回滚至历史版本（生成新版本号）。 */
    Integer rollbackTo(Long userId, Long itineraryId, Integer targetVersion);
}
