package com.travel.knowledge.service;

/**
 * WebEnrichWritebackService 公共契约（AD-2e 接口化）。
 *
 * <p>实现见 {@link WebEnrichWritebackServiceImpl}（@Service 在 Impl）；
 * 公有方法签名逐字提取（零语义变更）。</p>
 */
public interface WebEnrichWritebackService {

    /** 异步补全投递（7 天防抖，命中=false）。 */
    boolean submitAsyncFill(Long attractionId, String name, String city);

    /** 联网补全回写（ openHours/ticketPrice/description 三列）。 */
    boolean writeback(Long attractionId, String openHours, Double ticketPrice, String description);
}
