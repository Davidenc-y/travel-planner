package com.travel.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.travel.common.entity.Attraction;
import com.travel.knowledge.etl.AttractionEtlService;
import com.travel.knowledge.repository.AttractionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * M8-5：web_enrich 数据回写闭环（联网搜索退化为一次性补全工具）。
 *
 * <p>回写语义（复用爬虫 upsert 的「空字段保护」）：</p>
 * <ul>
 *   <li>只填充 null 列：WHERE {@code open_hours IS NULL OR ticket_price IS NULL}——
 *       已有值绝不覆盖（天然幂等并发安全）；</li>
 *   <li>7 天防抖：{@code enrich_source='web_enrich' 且 enrich_updated_at 在 7 天内} 时
 *       WHERE 不命中 → 跳过（等价 Redis SETNX web_enrich:{id} TTL 7 天，但零新依赖，
 *       与事实源同库）；</li>
 *   <li>成功后触发单条增量 ETL（ES/Milvus 同步；失败不阻断，定时 ETL 按 indexed=0 兜底）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebEnrichWritebackService {

    /** 防抖窗口（天） */
    private static final int DEBOUNCE_DAYS = 7;

    /** 回写后增量 ETL 专用虚拟线程（daemon，不阻塞检索主流程） */
    private static final ExecutorService ETL_EXECUTOR =
            Executors.newVirtualThreadPerTaskExecutor();

    private final AttractionMapper attractionMapper;
    private final AttractionEtlService etlService;

    /**
     * 幂等回写；返回 true=已写入并触发 ETL，false=跳过（已有值/防抖命中/参数非法）。
     */
    public boolean writeback(Long attractionId, String openHours, Double ticketPrice) {
        if (attractionId == null) {
            return false;
        }
        try {
            Attraction update = new Attraction();
            update.setId(attractionId);
            if (openHours != null && !openHours.isBlank()) {
                update.setOpenHours(openHours.trim());
            }
            if (ticketPrice != null) {
                update.setTicketPrice(BigDecimal.valueOf(ticketPrice));
            }
            update.setEnrichSource("web_enrich");
            update.setEnrichUpdatedAt(LocalDateTime.now());
            int rows = attractionMapper.update(update, new LambdaUpdateWrapper<Attraction>()
                    .eq(Attraction::getId, attractionId)
                    // 空字段保护：仅当至少一个目标字段仍为 null 时允许写入
                    .and(w -> w.isNull(Attraction::getOpenHours)
                            .or().isNull(Attraction::getTicketPrice))
                    // 7 天防抖：未补充过，或上次补充已超 7 天
                    .and(w -> w.isNull(Attraction::getEnrichSource)
                            .or().lt(Attraction::getEnrichUpdatedAt,
                                    LocalDateTime.now().minusDays(DEBOUNCE_DAYS))));
            if (rows == 0) {
                log.info("[WebEnrichWriteback] 跳过（已有值或 7 天防抖命中）: id={}", attractionId);
                return false;
            }
            log.info("[WebEnrichWriteback] 回写成功: id={}, openHours={}, ticketPrice={}",
                    attractionId, update.getOpenHours(), update.getTicketPrice());
            triggerIncrementalEtl(attractionId);
            return true;
        } catch (Exception e) {
            log.warn("[WebEnrichWriteback] 回写失败（不影响主流程）: id={}, err={}",
                    attractionId, e.getMessage());
            return false;
        }
    }

    /** 增量 ETL 异步执行：下次检索直接命中本地，搜索退化为一次性补全 */
    private void triggerIncrementalEtl(Long attractionId) {
        ETL_EXECUTOR.submit(() -> {
            try {
                Attraction a = attractionMapper.selectById(attractionId);
                if (a != null) {
                    etlService.etlOne(a);
                    log.info("[WebEnrichWriteback] 增量 ETL 完成: id={}", attractionId);
                }
            } catch (Exception e) {
                // indexed=0 由每天 03:00 定时 ETL 兜底
                log.warn("[WebEnrichWriteback] 增量 ETL 失败（定时 ETL 兜底）: id={}, err={}",
                        attractionId, e.getMessage());
            }
        });
    }
}
