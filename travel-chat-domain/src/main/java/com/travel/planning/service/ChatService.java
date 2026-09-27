package com.travel.planning.service;

import com.travel.common.dto.ChatResponseDTO;
import com.travel.common.entity.ChatMessage;
import com.travel.common.entity.ChatSession;
import com.travel.stream.service.ChatStreamExecutor;

import java.util.List;

/**
 * ChatService 公共契约（AD-2a 接口化 part2）。
 *
 * <p>实现见 {@link ChatServiceImpl}（@Service 在 Impl）；公有方法签名逐字提取（零语义变更）。
 * 三结果 record 与 T-5c 静态段组装方法迁入本接口——{@code ChatService.TurnStatusResult}/
 * {@code ChatService.insertResumeSection} 等既有 FQN 引用面零变动；继承
 * {@link ChatStreamExecutor} 流式契约（prepareStream 五/六/七参+runStream，实现类沿用 @Override）。</p>
 */
public interface ChatService extends ChatStreamExecutor {

    /**
     * 创建会话
     */
    String createSession(Long userId, String title);

    /**
     * 获取会话历史
     */
    List<ChatMessage> getHistory(Long userId, String sessionId);

    ChatResponseDTO sendMessage(String sessionId, String message, Long userId);

    /**
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)}
     */
    @Deprecated
    ChatResponseDTO sendMessage(String sessionId, String message, Long userId, String clientMessageId);

    /**
     * 发送消息并获取响应（M4-3 幂等 + M7 请求级模型）。
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)}
     */
    @Deprecated
    ChatResponseDTO sendMessage(String sessionId, String message, Long userId,
                                String clientMessageId, String model);

    /** M23（E1）：JSON 兜底路径同样携带锚定快照（与 SSE 路径 per-turn truth 一致）。
     * @deprecated Z-4c 收敛至 {@link #sendMessage(ChatTurnRequest)} */
    @Deprecated
    ChatResponseDTO sendMessage(String sessionId, String message, Long userId,
                                String clientMessageId, String model,
                                java.util.List<Long> anchorIds);

    /** Z-4c：sendMessage 主方法（参数对象收敛；真实体自六参版逐字搬运，参数访问机械替换）。 */
    ChatResponseDTO sendMessage(ChatTurnRequest req);

    /** Z-4c：prepareStream 主方法（参数对象收敛；真实体自七参版逐字搬运，参数访问机械替换）。 */
    ChatStreamExecutor.ChatStreamPrepared prepareStream(ChatTurnRequest req);

    /**
     * M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记）。
     */
    void interruptTurn(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-36：清除断点（用户发新消息时前端调用；prepareStream 侧另有双保险）。
     */
    void clearBreakpoint(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-42：查询轮次状态（前端刷新后恢复重试入口）。
     */
    TurnStatusResult getTurnStatus(Long userId, String sessionId, String clientMessageId);

    /**
     * M6-47：会话最近可恢复中断轮次（刷新/重进会话恢复重试入口）。
     */
    LatestInterruptedTurn getLatestInterruptedTurn(Long userId, String sessionId);

    List<ChatSession> listSessions(Long userId);

    /** M5-1：更新会话标题（手动编辑） */
    void updateTitle(Long userId, String sessionId, String title);

    /**
     * M4-4/P1-1：关闭会话（显式触发；禁止前端 beforeunload 调用——刷新会误归档）。
     */
    CloseSessionResult closeSession(Long userId, String sessionId);

    /** AB-5c-2：模型×时段使用采集（成功轮；测试直连点，M8-9j 可观测）。 */
    void collectModelUsage(ChatStreamExecutor.ChatStreamPrepared prepared,
                           ChatStreamExecutor.ChatStreamResult result);

    /** M6-42：轮次状态查询结果（status 为 null 表示无登记记录）。 */
    record TurnStatusResult(String status, boolean resumable, String userMessage) {
    }

    /** M6-47：最近可恢复中断轮次（clientMessageId 为 null 表示无可恢复轮次）。 */
    record LatestInterruptedTurn(
            String clientMessageId, String userMessage, boolean resumable) {
    }

    /** M4-4：关闭会话结果（archived=已归档；finalized=收口摘要已完成） */
    record CloseSessionResult(boolean archived, boolean finalized) {
    }

    /**
     * AB-5c：时段编号计算（AC-3a 起薄委托 {@link ProfileCollector#slotOf}——
     * TravelProfileService（AC-1c）与既有测试的 package-static 调用面零 diff 保持）。
     * AD-2a：自 Impl 迁入接口（原包私有 static→接口隐式 public，纯函数可见性放大零行为影响，留痕）。
     */
    static int slotOf(java.time.LocalDateTime t) {
        return ProfileCollector.slotOf(t);
    }

    /**
     * T-5c：composed 插入【已完成的子任务结果（中断前复用）】段——位置强制
     * 【当前问题】标记之前（冲突①：标记之后会被 ExplicitInputParser lastIndexOf
     * 尾段解析吞入，污染 destination/days 确定性解析）；每键一行=文本前 100 字摘要
     * （完整文本在 state 种子里，摘要仅供主代理路由参考）；marker 缺失则原样返回
     * （不注入，仅 state 种子兜底——段为软引导）。public static=planning 回归测试直连。
     * AD-2a：自 Impl 逐字迁移至接口（调用方 FQN 零变）。
     */
    static String insertResumeSection(String composed,
                                      java.util.Map<String, String> resumeSeed) {
        if (composed == null || resumeSeed == null || resumeSeed.isEmpty()) {
            return composed;
        }
        StringBuilder section = new StringBuilder("\n【已完成的子任务结果（中断前复用）】\n");
        resumeSeed.forEach((key, text) -> {
            String t = text == null ? "" : text.trim();
            section.append(key).append('=')
                    .append(t.length() > 100 ? t.substring(0, 100) : t)
                    .append('\n');
        });
        int qIdx = composed.indexOf(com.travel.common.prompt.Markers.CURRENT_QUESTION);
        if (qIdx < 0) {
            return composed;
        }
        return composed.substring(0, qIdx) + section + composed.substring(qIdx);
    }
}
