import type { R } from '@/types';
import { consumeSseStream, type SseStreamHandlers } from '../sse';
import { PLANNING_BASE, isAbortError, planningApi } from './http';

// M6-34：聊天 SSE 灰度切换——NEXT_PUBLIC_STREAM_BASE 指向 WebFlux(8083) 时聊天流走
// 响应式传输层，其余会话/消息 JSON API 仍走 planning(8081)；未配置时回退 PLANNING_BASE
const STREAM_BASE = process.env.NEXT_PUBLIC_STREAM_BASE || PLANNING_BASE;
// R3（02-11 §10.2-R7）：灰度目标网络级失败后的降级记忆（会话级，刷新后重试灰度）
let sseFallbackToLocal = false;

/**
 * R3/M8-9j：业务码错误视为正常响应语义，不触发灰度降级。
 *
 * <p>兼容两种形态：HTTP 非 2xx 的 axios 错误（err.response.data.code）与
 * SSE error 事件抛出的错误（useChatStream.onError 仅设置 err.code，无
 * response.data）——后者此前被误判为网络错误，导致 40303/40904 等业务错误
 * 被重复发送到 planning(8081)，同一消息双端执行。</p>
 */
function isBusinessError(err: unknown): boolean {
  const e = err as { response?: { data?: { code?: number } }; code?: number } | undefined;
  return typeof e?.response?.data?.code === 'number' || typeof e?.code === 'number';
}

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
   *  R3（02-11 §10.2-R7）：灰度目标网络级失败时自动回退 planning(8081) 并记忆降级
   *  （仅网络错误/5xx，业务码 40904/40005 等属于正常响应语义，不触发降级）。 */
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

    // R3：未配置灰度目标、或已降级记忆 → 直接走 planning（无回退逻辑参与）
    if (STREAM_BASE === PLANNING_BASE || sseFallbackToLocal) {
      return attempt(PLANNING_BASE);
    }
    return attempt(STREAM_BASE).catch(async (err: unknown) => {
      if (signal.aborted || isAbortError(err)) throw err; // 主动取消不回退
      if (isBusinessError(err)) throw err; // 业务码=正常响应语义（40904 重试/40005 换模型等）
      // 网络级失败（连接拒绝/DNS/非 2xx 无业务码）→ 记忆降级并回退 planning
      console.warn('[api] SSE 灰度目标不可用，本次及后续聊天流回退 planning(8081)');
      sseFallbackToLocal = true;
      return attempt(PLANNING_BASE);
    });
  },
};
