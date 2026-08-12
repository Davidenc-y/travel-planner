package com.travel.planning.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.travel.common.dto.ItineraryGenerateRequestDTO;
import com.travel.common.dto.ItineraryResponseDTO;
import com.travel.common.dto.ItineraryResponseDTO.*;
import com.travel.common.entity.Itinerary;
import com.travel.common.enums.ItineraryStatus;
import com.travel.common.exception.ItineraryGenerationException;
import com.travel.common.result.PageResult;
import com.travel.common.util.JsonUtils;
import com.travel.planning.repository.ItineraryMapper;
import com.travel.planning.workflow.TravelWorkflowBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 行程服务（M2-2 增强版）
 *
 * <p>M2-2 增强：</p>
 * <ul>
 *   <li>JSON 解析：将 content JSON 解析为 DayPlan 列表</li>
 *   <li>思维导图：调用 MindmapGenerator 生成结构化思维导图</li>
 *   <li>画像更新：行程生成后自动更新用户旅游画像</li>
 *   <li>估算费用：从 budgetEstimate JSON 提取 totalCost</li>
 *   <li>超时保护：工作流执行添加超时控制</li>
 * </ul>
 *
 * @author david_ency
 * @since 1.0-SNAPSHOT
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryService {

    /**
     * 工作流整体执行超时（秒）：硬性退出边界。
     * 防止 LLM 调用异常/重试异常导致请求无限悬挂（F23 修复，配合重试上限）。
     */
    private static final long MAX_EXECUTION_SECONDS = 300;

    /**
     * 工作流执行专用线程池：使用虚拟线程（Java 21）。
     *
     * <p>F24 补强：原 CachedThreadPool 的线程为非 daemon，超时后任务仍会在后台继续执行，
     * 且非 daemon 线程会阻塞 JVM 优雅退出；虚拟线程为 daemon 且轻量，
     * 与 {@link CompletableFuture#cancel(boolean)} 配合可及时中断 graph.invoke 的阻塞等待。</p>
     */
    private static final ExecutorService WORKFLOW_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final ItineraryMapper itineraryMapper;
    private final TravelWorkflowBuilder workflowBuilder;
    private final TravelProfileService profileService;
    private final MindmapGenerator mindmapGenerator;

    /**
     * 生成行程（调用 StateGraph 工作流 + 持久化 + 画像更新）
     */
    public ItineraryResponseDTO generate(ItineraryGenerateRequestDTO req, Long userId) {
        // 1. 幂等检查
        Itinerary existing = itineraryMapper.findByClientRequestId(req.getClientRequestId());
        if (existing != null) {
            log.info("幂等命中: clientRequestId={}", req.getClientRequestId());
            return toResponseDTO(existing);
        }

        // 2. 构建工作流初始状态
        String userInput = buildUserInput(req);
        Map<String, Object> initialState = new HashMap<>();
        initialState.put("userInput", userInput);
        initialState.put("userId", userId);
        initialState.put("retryCount", 0);

        // 3. 执行工作流
        long start = System.currentTimeMillis();
        try {
            CompiledGraph graph = workflowBuilder.buildWorkflow();
            OverAllState finalState;
            CompletableFuture<Optional<OverAllState>> future = null;
            try {
                // graph.invoke 为阻塞调用且无超时参数，用 CompletableFuture 提供硬性执行时间边界
                // （F23 修复：防止预算回退死循环/LLM 卡死时请求无限悬挂）
                future = CompletableFuture.supplyAsync(() -> graph.invoke(initialState), WORKFLOW_EXECUTOR);
                finalState = future.orTimeout(MAX_EXECUTION_SECONDS, TimeUnit.SECONDS)
                        .get()
                        .orElseThrow(() -> new ItineraryGenerationException("工作流未返回最终状态"));
            } catch (ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof TimeoutException) {
                    // F24 补强：超时后立即 cancel(true) 中断后台 graph.invoke（虚拟线程 + Mono.block()
                    // 均可响应中断），避免 orTimeout 只让调用方返回、底层任务继续空转消耗 DashScope 额度。
                    if (future != null) {
                        future.cancel(true);
                    }
                    log.error("行程生成超时（>{}s）: destination={}, days={}", MAX_EXECUTION_SECONDS,
                            req.getDestination(), req.getDays());
                    throw new ItineraryGenerationException(
                            "行程生成超时（超过 " + MAX_EXECUTION_SECONDS + " 秒），请稍后重试", ee);
                }
                if (cause instanceof RuntimeException re) {
                    throw re; // 保留原始异常（含 DashScope 上游错误），由外层统一包装
                }
                throw new ItineraryGenerationException(
                        cause != null ? cause.getMessage() : "行程生成失败", ee);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ItineraryGenerationException("行程生成被中断", ie);
            }

            String itineraryJson = finalState.value("itinerary", "").toString();
            String mindmapJson = finalState.value("mindmap", "").toString();

            long cost = System.currentTimeMillis() - start;
            log.info("工作流执行完成: 耗时={}ms, itineraryLen={}, mindmapLen={}",
                    cost, itineraryJson.length(), mindmapJson.length());

            // 4. 提取估算费用
            BigDecimal estimatedCost = extractEstimatedCost(itineraryJson);

            // 5. 生成思维导图（如果工作流未生成）
            String finalMindmap = mindmapJson;
            if (finalMindmap == null || finalMindmap.isBlank()) {
                MindmapData mindmap = mindmapGenerator.generate(
                        req.getDestination() + req.getDays() + "日游",
                        req.getDestination(), req.getDays(),
                        req.getBudget() != null ? req.getBudget().toString() : null,
                        itineraryJson);
                finalMindmap = JsonUtils.toJson(mindmap);
            }

            // 6. 持久化
            Itinerary entity = new Itinerary();
            entity.setUserId(userId);
            entity.setDestination(req.getDestination());
            entity.setDays(req.getDays());
            entity.setBudget(req.getBudget());
            entity.setInterests(JsonUtils.toJson(req.getInterests()));
            entity.setParty(req.getParty());
            entity.setStartDate(req.getStartDate());
            entity.setStatus(ItineraryStatus.GENERATED.name());
            entity.setTitle(req.getDestination() + req.getDays() + "日游");
            entity.setContent(itineraryJson);
            entity.setMindmapData(finalMindmap);
            entity.setEstimatedCost(estimatedCost);
            entity.setClientRequestId(req.getClientRequestId());
            itineraryMapper.insert(entity);

            log.info("行程生成成功: id={}, destination={}, cost={}", entity.getId(), req.getDestination(), estimatedCost);

            // 7. 更新用户画像（非阻塞，失败不影响主流程）
            profileService.recordTrip(userId, req.getDestination(),
                    JsonUtils.toJson(req.getInterests()), entity.getTitle());

            return toResponseDTO(entity);

        } catch (ItineraryGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("行程生成失败: {}", e.getMessage(), e);
            throw new ItineraryGenerationException(buildUpstreamMessage(e), e);
        }
    }

    /**
     * 沿异常链查找 DashScope 的 WebClient 响应异常，把上游响应体（如
     * AllocationQuota.FreeTierOnly）透出到业务异常 message，便于快速定位配额/鉴权问题。
     */
    private static String buildUpstreamMessage(Throwable e) {
        Throwable cur = e;
        while (cur != null) {
            if (cur instanceof WebClientResponseException wcre) {
                String body = wcre.getResponseBodyAsString();
                if (body != null && !body.isBlank()) {
                    return cur.getMessage() + " | upstreamBody="
                            + body.substring(0, Math.min(300, body.length()));
                }
            }
            cur = cur.getCause();
        }
        return e != null ? e.getMessage() : "unknown";
    }

    /**
     * 查询行程详情（含 dayPlans + mindmap 解析）
     */
    public ItineraryResponseDTO getById(Long id) {
        Itinerary entity = itineraryMapper.selectById(id);
        if (entity == null) {
            throw new ItineraryGenerationException("行程不存在: " + id);
        }
        return toResponseDTO(entity);
    }

    /**
     * 分页查询用户行程
     */
    public PageResult<ItineraryResponseDTO> listByUserId(Long userId, int page, int size) {
        int offset = (page - 1) * size;
        List<Itinerary> list = itineraryMapper.findByUserId(userId, offset, size);
        long total = itineraryMapper.countByUserId(userId);
        return PageResult.of(list.stream().map(this::toResponseDTO).toList(), total, page, size);
    }

    /**
     * 删除行程
     */
    public void delete(Long id) {
        itineraryMapper.deleteById(id);
        log.info("行程删除: id={}", id);
    }

    // ==================== 内部方法 ====================

    /**
     * 构建用户输入文本
     */
    private String buildUserInput(ItineraryGenerateRequestDTO req) {
        return String.format("""
                目的地：%s
                天数：%d
                预算：%s
                兴趣：%s
                出行人员：%s
                开始日期：%s
                """,
                req.getDestination(),
                req.getDays(),
                req.getBudget() != null ? req.getBudget() + "元" : "不限",
                req.getInterests() != null ? req.getInterests() : "不限",
                req.getParty() != null ? req.getParty() : "不限",
                req.getStartDate() != null ? req.getStartDate() : "未指定");
    }

    /**
     * 从行程 JSON 中提取估算总费用
     */
    private BigDecimal extractEstimatedCost(String itineraryJson) {
        try {
            Map<String, Object> content = JsonUtils.fromJson(itineraryJson, Map.class);
            if (content == null) return null;

            // 尝试从 budgetEstimate.totalCost 提取
            Object budgetEstimate = content.get("budgetEstimate");
            if (budgetEstimate instanceof Map) {
                Object totalCost = ((Map<String, Object>) budgetEstimate).get("totalCost");
                if (totalCost != null) {
                    return new BigDecimal(totalCost.toString());
                }
            }
        } catch (Exception e) {
            log.warn("提取估算费用失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Entity → ResponseDTO（含 dayPlans + mindmap 解析）
     */
    private ItineraryResponseDTO toResponseDTO(Itinerary entity) {
        ItineraryResponseDTO dto = ItineraryResponseDTO.builder()
                .id(entity.getId())
                .title(entity.getTitle())
                .destination(entity.getDestination())
                .days(entity.getDays())
                .estimatedCost(entity.getEstimatedCost())
                .generatedAt(entity.getCreatedAt() != null ? entity.getCreatedAt().toString() : null)
                .build();

        // 解析 content JSON → dayPlans
        if (entity.getContent() != null && !entity.getContent().isBlank()) {
            try {
                Map<String, Object> content = JsonUtils.fromJson(entity.getContent(), Map.class);
                if (content != null && content.containsKey("routePlan")) {
                    Object routePlan = content.get("routePlan");
                    if (routePlan instanceof Map) {
                        Object days = ((Map<String, Object>) routePlan).get("days");
                        if (days instanceof List) {
                            dto.setDayPlans(parseDayPlans((List<?>) days));
                        }
                    }
                }
                // 预算分配明细透出（M2-5 输出优化）：content.budgetEstimate -> dto.budgetBreakdown
                if (content != null && content.containsKey("budgetEstimate")) {
                    dto.setBudgetBreakdown(parseBudgetBreakdown(content.get("budgetEstimate")));
                }
            } catch (Exception e) {
                log.warn("解析 dayPlans 失败: itineraryId={}", entity.getId());
            }
        }

        // 解析 mindmapData JSON → MindmapData
        if (entity.getMindmapData() != null && !entity.getMindmapData().isBlank()) {
            try {
                MindmapData mindmap = JsonUtils.fromJson(entity.getMindmapData(), MindmapData.class);
                dto.setMindmap(mindmap);
            } catch (Exception e) {
                log.warn("解析 mindmap 失败: itineraryId={}", entity.getId());
            }
        }

        return dto;
    }

    /**
     * 将 content.budgetEstimate（Map/JSON）解析为 {@link BudgetBreakdown}。
     * 字段缺失或类型异常时按 null 容错，不影响主流程。
     */
    @SuppressWarnings("unchecked")
    private BudgetBreakdown parseBudgetBreakdown(Object budgetEstimate) {
        if (!(budgetEstimate instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) budgetEstimate;
        return BudgetBreakdown.builder()
                .ticketCost(toBigDecimal(map.get("ticketCost")))
                .mealCost(toBigDecimal(map.get("mealCost")))
                .transportCost(toBigDecimal(map.get("transportCost")))
                .hotelCost(toBigDecimal(map.get("hotelCost")))
                .otherCost(toBigDecimal(map.get("otherCost")))
                .totalCost(toBigDecimal(map.get("totalCost")))
                .perPersonCost(toBigDecimal(map.get("perPersonCost")))
                .currency(map.get("currency") != null ? map.get("currency").toString() : null)
                .notes(map.get("notes") != null ? map.get("notes").toString() : null)
                .build();
    }

    /**
     * 将 Number / String 安全转为 BigDecimal，null 或非法值返回 null。
     */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析每日计划列表
     */
    @SuppressWarnings("unchecked")
    private List<DayPlan> parseDayPlans(List<?> dayList) {
        List<DayPlan> result = new ArrayList<>();
        for (Object item : dayList) {
            if (item instanceof Map) {
                Map<String, Object> dayMap = (Map<String, Object>) item;
                DayPlan day = DayPlan.builder()
                        .day((Integer) dayMap.get("day"))
                        .date((String) dayMap.get("date"))
                        .summary((String) dayMap.get("summary"))
                        .transportMode((String) dayMap.get("transportMode"))
                        .hotelSuggestion((String) dayMap.get("hotelSuggestion"))
                        .build();

                // 解析景点访问列表
                Object attractions = dayMap.get("attractions");
                if (attractions instanceof List) {
                    List<AttractionVisit> visits = new ArrayList<>();
                    for (Object attr : (List<?>) attractions) {
                        if (attr instanceof Map) {
                            Map<String, Object> attrMap = (Map<String, Object>) attr;
                            visits.add(AttractionVisit.builder()
                                    .name((String) attrMap.get("name"))
                                    .timeSlot((String) attrMap.get("timeSlot"))
                                    .cost(toBigDecimal(attrMap.get("cost")))
                                    .notes((String) attrMap.get("notes"))
                                    .build());
                        }
                    }
                    day.setAttractions(visits);
                }
                result.add(day);
            }
        }
        return result;
    }
}
