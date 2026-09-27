package com.travel.planning.service;

/**
 * Z-4b：会话查询/生命周期服务接口（建会话/历史/关会话）。
 *
 * <p>AD-2a 接口化：实现见 {@link ChatSessionQueryServiceImpl}（@Service 在 Impl）；
 * 公有方法签名自原实现类逐字提取（零语义变更）。</p>
 */
public interface ChatSessionQueryService {

    /**
     * 创建会话
     */
    String createSession(Long userId, String title);

    /**
     * 获取会话历史
     */
    java.util.List<com.travel.common.entity.ChatMessage> getHistory(Long userId, String sessionId);

    /**
     * M4-4/P1-1：关闭会话（显式触发；禁止前端 beforeunload 调用——刷新会误归档）。
     *
     * <p>幂等：已 ARCHIVED 直接返回；条件更新 ACTIVE→ARCHIVED 防并发双关。</p>
     */
    ChatService.CloseSessionResult closeSession(Long userId, String sessionId);
}
