import type { R } from '@/types';
import { consumeSseStream, type SseStreamHandlers } from '../sse';
import { PLANNING_BASE, planningApi } from './http';

// U-4a：webflux 退役——聊天 SSE 恒用 planning(8081)（8083 基址与灰度回退分支随模块删除）

// ==================== Chat ====================
export const chatApi = {
  createSession: (title?: string) =>
    planningApi.post<R<string>>('/api/v1/chat/sessions', { title }),
  listSessions: () =>
    planningApi.get<R<import('@/types').ChatSession[]>>('/api/v1/chat/sessions'),
  getHistory: (sessionId: string) =>
    planningApi.get<R<import('@/types').ChatMessage[]>>(`/api/v1/chat/sessions/${sessionId}/history`),
  /** M4-9：clientMessageId 为消息幂等键——超时/40904 退避重试须携带同键 */
  /** M7 Batch 3：model 可选——请求级模型（null=角色默认） */
  sendMessage: (sessionId: string, message: string, clientMessageId?: string, model?: string,
                anchoredItineraryIds?: number[], preferences?: Record<string, unknown>) =>
    planningApi.post<R<import('@/types').ChatResponse>>(`/api/v1/chat/sessions/${sessionId}/messages`, {
      message,
      clientMessageId,
      ...(model ? { model } : {}),
      ...(anchoredItineraryIds && anchoredItineraryIds.length > 0 ? { anchoredItineraryIds } : {}),
      ...(preferences && Object.keys(preferences).length > 0 ? { preferences } : {}),
    }),
  /** M4-9：显式关闭会话（归档+收口摘要；禁止 beforeunload 触发） */
  /** M28-15：系统提示消息落库（锚定切换等事件，会话内中央小字持久显示） */
  appendSystemNote: (sessionId: string, content: string) =>
    planningApi.post<R<void>>(`/api/v1/chat/sessions/${sessionId}/system-note`, { content }),
  closeSession: (sessionId: string) =>
    planningApi.post<R<{ archived: boolean; finalized: boolean }>>(`/api/v1/chat/sessions/${sessionId}/close`),
  /** M5-1：更新会话标题（双击编辑保存） */
  updateTitle: (sessionId: string, title: string) =>
    planningApi.put<R<void>>(`/api/v1/chat/sessions/${sessionId}/title`, { title }),
  /** M6-36：中断在途轮次（PENDING → FAILED + Redis 中断标记） */
  interruptTurn: (sessionId: string, clientMessageId: string) =>
    planningApi.post<R<void>>(`/api/v1/chat/sessions/${sessionId}/turns/${clientMessageId}/interrupt`),
  /** M6-36：清除断点（用户发新消息时调用；后端 prepareStream 另有双保险） */
  clearBreakpoint: (sessionId: string, clientMessageId: string) =>
    planningApi.delete<R<void>>(`/api/v1/chat/sessions/${sessionId}/turns/${clientMessageId}/breakpoint`),
  /** M6-42：查询轮次状态（刷新后校验本地中断记录是否仍可恢复重试） */
  getTurnStatus: (sessionId: string, clientMessageId: string) =>
    planningApi.get<R<import('@/types').TurnStatus>>(
      `/api/v1/chat/sessions/${sessionId}/turns/${clientMessageId}`),
  /** M6-47：查询会话最近可恢复中断轮次（浏览器刷新后恢复重试入口，不依赖本地 key） */
  getLatestInterruptedTurn: (sessionId: string) =>
    planningApi.get<R<import('@/types').LatestInterruptedTurn>>(
      `/api/v1/chat/sessions/${sessionId}/interrupted-turn`),
  /** M6：流式发送（SSE）——POST /messages/stream，事件回调驱动思考气泡与流式文本。
   *  U-4a：webflux 已退役，SSE 恒用 planning(8081)（原 R3 灰度/回退分支删除）。 */
  sendMessageStream: (
    sessionId: string,
    message: string,
    clientMessageId: string,
    signal: AbortSignal,
    handlers: SseStreamHandlers,
    lastEventId?: string,
    model?: string,
    anchoredItineraryIds?: number[],
    preferences?: Record<string, unknown>,
  ) => {
    const headers: Record<string, string> = {};
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) headers.Authorization = `Bearer ${token}`;
    }
    // P1：断线续传——携带最近收到的事件 id（仅 COMPLETED 重放生效）
    if (lastEventId) headers['Last-Event-ID'] = lastEventId;

    const attempt = (base: string) =>
      consumeSseStream(
        `${base}/api/v1/chat/sessions/${sessionId}/messages/stream`,
        {
          message,
          clientMessageId,
          ...(model ? { model } : {}),
          ...(anchoredItineraryIds && anchoredItineraryIds.length > 0 ? { anchoredItineraryIds } : {}),
          ...(preferences && Object.keys(preferences).length > 0 ? { preferences } : {}),
        },
        headers,
        signal,
        handlers,
    );

    // U-4a：webflux 已退役，SSE 恒用 planning(8081)（原 8083 灰度/回退分支删除）
    return attempt(PLANNING_BASE);
  },
};
