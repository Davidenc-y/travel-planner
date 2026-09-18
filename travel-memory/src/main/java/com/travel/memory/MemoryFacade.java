package com.travel.memory;

import com.travel.common.entity.TravelProfile;
import com.travel.common.entity.UserBehaviorProfile;
import com.travel.memory.knowledge.dto.ConsensusEntry;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆体系统一门面（R7.2 阶段一仅定义 + 映射表，阶段二经人工确认后迁移行为）。
 *
 * <p>四级记忆对应实现类（阶段二门面方法逐个镜像其既有公共入口）：
 * <ul>
 *   <li>shortterm → memory.shortterm.SessionMemoryServiceImpl（经 SessionMemoryPort）</li>
 *   <li>longterm → service.TravelProfileService（经 ProfilePort）/ memory.longterm.behavior.BehaviorProfileService</li>
 *   <li>anchor → memory.anchor.SessionAnchorStore</li>
 *   <li>knowledge → memory.knowledge.SessionFactConsolidator</li>
 * </ul>
 *
 * <p>B3.3（阶段二首批收编）：按 R7-memory-mapping 取"只读、被外部调用最频"两入口——
 * anchor 读取 {@link #getAnchors(String)}（映射表三调用点：ChatService/SessionAnchorController/
 * ItineraryVersionPortImpl）与 profile 读取 {@link #getOrCreateProfile(Long)}（映射表七调用点，
 * longterm 级建议门面首入口）；MI-3 增补 shortterm 读入口 {@link #getSummary(String)} 与
 * knowledge 级 facts 触发入口 {@link #consolidateFacts(List)}。</p>
 *
 * <p>R7 阶段二收编（MM-1a.1）：按 R7-memory-mapping §汇总表建议清单补齐其余门面方法
 * （shortterm 生命周期/组装/工具 10、longterm 画像写与行为画像 4、anchor 写与渲染 2、
 * knowledge 渲染 1），全部为既有实现类公共入口的镜像签名（纯委托，零新语义）；
 * getSummaryInfo 等实现类内部项按映射表保留 Port/实现类语义，不入门面。</p>
 */
public interface MemoryFacade {

    /**
     * 会话锚定行程 id 列表读取（只读）。
     *
     * <p>委托 anchor 级实现类 SessionAnchorStore#getAnchors（M28-13 自动锚定判定与
     * planning 锚定面板同源入口）；无锚定返回空列表。</p>
     */
    List<Long> getAnchors(String sessionId);

    /**
     * 用户画像读取（首次惰性建档；映射表 getOrCreate 的门面名 getOrCreateProfile）。
     *
     * <p>委托 longterm 级实现类 service.TravelProfileService#getOrCreate（经 ProfilePort 同源逻辑）。</p>
     */
    TravelProfile getOrCreateProfile(Long userId);

    /**
     * 会话摘要读取（shortterm 级读入口；R7-memory-mapping 门面名 getSummary）——MI-3 增补。
     *
     * <p>委托 shortterm 级实现类 SessionMemoryServiceImpl#getSummaryOrEmpty（经 SessionMemoryPort 同源入口）。</p>
     */
    String getSummary(String sessionId);

    /**
     * 会话事实共识合并（knowledge 级 facts 触发入口；R7-memory-mapping 门面名 consolidateFacts）——MI-3 增补。
     *
     * <p>委托 knowledge 级实现类 SessionFactConsolidator#consolidate（ChatBudgetStep 同源入口）。</p>
     */
    List<ConsensusEntry> consolidateFacts(List<Map<String, Object>> hits);

    // ===== R7 阶段二收编：shortterm 级（委托 SessionMemoryPort 同源入口）=====

    /**
     * M3-9：请求开始（清空请求内消息快照；R7 阶段二收编，透传）。
     *
     * <p>委托 shortterm 级实现类 SessionMemoryServiceImpl#beginRequest（经 SessionMemoryPort 同源入口）。</p>
     */
    void beginRequest();

    /**
     * M3-9：请求结束（清理 ThreadLocal；R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #endRequest。</p>
     */
    void endRequest();

    /**
     * 组装最近 maxTurns 轮历史上下文（含 token 截断；R7 阶段二收编，映射表门面名 composeHistory）。
     *
     * <p>委托同上实现类 #composeHistoryContext；无历史返回空串。</p>
     */
    String composeHistory(String sessionId, int maxTurns);

    /**
     * 异步生成并保存会话摘要（R7 阶段二收编，透传；不阻塞调用方）。
     *
     * <p>委托同上实现类 #summarizeAsync。</p>
     */
    void summarizeAsync(String sessionId);

    /**
     * M4-4：会话收口摘要——绕过 refresh-turns 门控的全量重算（R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #finalizeSummary；true=已写入 final 摘要，false=空会话/CAS 冲突/生成失败。</p>
     */
    boolean finalizeSummary(String sessionId);

    /**
     * 组装最近 turns 轮原文（无【历史对话】头；R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #composeRecentWindow。</p>
     */
    String composeRecentWindow(String sessionId, int turns);

    /**
     * 统计会话中 user 消息轮数（R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #countUserTurns。</p>
     */
    int countUserTurns(String sessionId);

    /**
     * 汇总全量消息的 token（R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #totalHistoryTokens。</p>
     */
    int totalHistoryTokens(String sessionId);

    /**
     * 估算文本 token（R7 阶段二收编，门面工具面——映射表 * 裁点经方案 MM-1a 操作③裁决收编）。
     *
     * <p>委托同上实现类 #estimateTokens。</p>
     */
    int estimateTokens(String text);

    /**
     * 按 token 预算截断文本（R7 阶段二收编，门面工具面，裁决口径同 {@link #estimateTokens(String)}）。
     *
     * <p>委托同上实现类 #truncateByTokens。</p>
     */
    String truncateByTokens(String text, int maxTokens);

    // ===== R7 阶段二收编：longterm 级 =====

    /**
     * 用户画像更新（R7 阶段二收编；映射表裁决：门面按 M11-4 六参全形收敛 5/6 参两重载）。
     *
     * <p>委托 longterm 级实现类 service.TravelProfileService#update（六参全形；
     * consumeLevel=null 时不覆盖，与既有 5 参重载委托语义一致）。</p>
     */
    TravelProfile updateProfile(Long userId, String preferredDestinations, String preferredInterests,
                                String budgetRange, String travelStyle, String consumeLevel);

    /**
     * 行程生成后自动更新画像（R7 阶段二收编，透传）。
     *
     * <p>委托同上实现类 #recordTrip。</p>
     */
    void recordTrip(Long userId, String destination, String interests, String title,
                    BigDecimal budget, String party);

    /**
     * 用户行为画像读取（R7 阶段二收编，透传）。
     *
     * <p>委托 longterm 级 BehaviorProfileService#getBehavior（无接口类，映射表附注：直接委托类实例）。</p>
     */
    Optional<UserBehaviorProfile> getBehavior(Long userId);

    /**
     * 行为画像按开关重算（R7 阶段二收编，映射表门面名 recomputeBehaviorIfEnabled）。
     *
     * <p>委托同上 BehaviorProfileService#recomputeIfEnabled。</p>
     */
    void recomputeBehaviorIfEnabled(Long userId);

    // ===== R7 阶段二收编：anchor 级（委托 SessionAnchorStore）=====

    /**
     * 锚定列表替换（R7 阶段二收编，透传）。
     *
     * <p>委托 anchor 级实现类 SessionAnchorStore#replaceAnchors（R4.2 薄封装同源入口）。</p>
     */
    List<Long> replaceAnchors(Long userId, String sessionId, List<Long> requested);

    /**
     * 锚定段落渲染（R7 阶段二收编，映射表门面名 renderAnchorSection）。
     *
     * <p>委托同上实现类 #renderSection。</p>
     */
    String renderAnchorSection(Long userId, List<Long> anchorIds);

    // ===== R7 阶段二收编：knowledge 级（委托 SessionFactConsolidator）=====

    /**
     * 事实共识渲染（R7 阶段二收编，映射表门面名 renderFacts）。
     *
     * <p>委托 knowledge 级实现类 SessionFactConsolidator#render；映射表 renderFacts/renderConsensus
     * 二选一裁决取与实现类一一镜像的透传形态，不合并为一步法。</p>
     */
    String renderFacts(List<ConsensusEntry> entries);
}
