/**
 * 前端 API 集中管理模块（F87 重构）。
 *
 * 架构：单一 axios 客户端工厂（planning/knowledge 双 baseURL）+ 领域 API 对象
 * （auth/itinerary/chat/attraction）集中于此，页面禁止直接拼 URL；
 * 401 时单飞刷新 accessToken 并重放原请求。
 *
 * 端点边界（F87 明确）：
 *  - 前端只允许调用下列"用户面端点"；
 *  - ETL（/api/v1/etl/*）、RAG 调试（/api/v1/rag/*）、会话知识（/api/v1/memory/*）
 *    为后端集成与接口测试专用，前端【不】提供任何调用封装，页面也不得使用。
 */

import axios, { AxiosInstance } from 'axios';
import type { AuthResponse, R } from '@/types';
import { consumeSseStream, type SseStreamHandlers } from './sse';

const PLANNING_BASE = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';
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

// ==================== 认证辅助（F87） ====================

/** M5-1：显式登出期间抑制 401 自动跳登录——登出统一回首页，避免被在途 401 覆盖 */
let suppressAuthRedirect = false;

export function setSuppressAuthRedirect(value: boolean): void {
  suppressAuthRedirect = value;
}

function clearAuth(): void {
  if (typeof window === 'undefined') return;
  localStorage.removeItem('accessToken');
  localStorage.removeItem('refreshToken');
  localStorage.removeItem('userId');
  localStorage.removeItem('username');
  // F94：401 清凭据时同步清理 cookie（否则 middleware 仍放行，页面与守卫状态分裂）
  document.cookie = 'accessToken=; path=/; max-age=0; SameSite=Lax';
}

function redirectToLogin(): void {
  if (typeof window === 'undefined' || suppressAuthRedirect) return;
  if (window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
}

let refreshPromise: Promise<string | null> | null = null;

/** 单飞刷新：并发 401 只触发一次 refresh；成功更新 localStorage 并返回新 token */
async function tryRefresh(): Promise<string | null> {
  if (typeof window === 'undefined') return null;
  const refreshToken = localStorage.getItem('refreshToken');
  if (!refreshToken) return null;
  try {
    const res = await axios.post<R<AuthResponse>>(
      `${PLANNING_BASE}/api/v1/auth/refresh`,
      { refreshToken },
      { timeout: 15000 },
    );
    const data = res.data.data;
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('refreshToken', data.refreshToken);
    localStorage.setItem('userId', String(data.userId));
    localStorage.setItem('username', data.username);
    if (typeof document !== 'undefined') {
      document.cookie = `accessToken=${encodeURIComponent(data.accessToken)}; path=/; max-age=86400; SameSite=Lax`;
    }
    return data.accessToken;
  } catch {
    clearAuth();
    return null;
  }
}

function createClient(baseURL: string): AxiosInstance {
  const client = axios.create({ baseURL, timeout: 120000 });
  client.interceptors.request.use((config) => {
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      const userId = localStorage.getItem('userId');
      if (userId) {
        config.headers['X-User-Id'] = userId;
      }
    }
    return config;
  });
  client.interceptors.response.use(
    (res) => res,
    (error) => {
      const status = error.response?.status;
      const original = error.config as { _retried?: boolean } | undefined;
      // F87：401 先尝试单飞刷新，成功后重放原请求；刷新失败才清本地并跳登录
      if (status === 401 && original && !original._retried && typeof window !== 'undefined') {
        original._retried = true;
        refreshPromise = refreshPromise ?? tryRefresh();
        return refreshPromise.then((token) => {
          refreshPromise = null;
          if (token) {
            error.config.headers = {
              ...error.config.headers,
              Authorization: `Bearer ${token}`,
            };
            return client(error.config);
          }
          clearAuth();
          redirectToLogin();
          return Promise.reject(error);
        });
      }
      if (status === 401) {
        clearAuth();
        redirectToLogin();
      }
      return Promise.reject(error);
    }
  );
  return client;
}

export const planningApi = createClient(PLANNING_BASE);
export const knowledgeApi = createClient(KNOWLEDGE_BASE);

/**
 * 统一错误信息提取（F87）：优先后端 message，其次 axios 错误文本。
 * R2/S5：40301 限流统一为固定友好文案（后端 message 可能含内部细节）。
 * 页面 toast 一律使用本函数，避免重复拼装。
 */
export function getErrorMessage(err: unknown): string {
  const e = err as { response?: { data?: { message?: string; code?: number } }; message?: string } | undefined;
  if (e?.response?.data?.code === 40301) {
    return '操作过于频繁，请稍后再试';
  }
  // M8-9h：模型额度不足——明确提示用户切换模型或检查账户额度
  if (e?.response?.data?.code === 40303) {
    // 优先后端消息（已动态携带模型名），缺失时才用通用兜底
    return e?.response?.data?.message
      || '模型额度不足：当前模型不可用，请切换其他可用模型，或在 DashScope 控制台充值/关闭“仅免费额度”后重试';
  }
  return e?.response?.data?.message || e?.message || '请求失败，请稍后重试';
}

/** R4：从未知错误中安全提取 HTTP/业务错误码（兼容 axios 双形态：HTTP 对齐 / 业务码双轨） */
export function httpErrorCode(err: unknown): number | undefined {
  const e = err as { response?: { data?: { code?: number } }; code?: number } | undefined;
  return e?.response?.data?.code ?? e?.code;
}

/** R4：请求是否被主动中止（AbortController） */
export function isAbortError(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === 'AbortError';
}

// ==================== Auth ====================
export const authApi = {
  login: (username: string, password: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/login', { username, password }),
  register: (username: string, password: string, email?: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/register', { username, password, email }),
  /** F87：退出登录——通知后端注销 Redis refreshToken（best-effort，失败仅清本地） */
  logout: () => planningApi.post<R<void>>('/api/v1/auth/logout'),
};

// ==================== User（F121：个人资料/头像） ====================
export const userApi = {
  me: () =>
    planningApi.get<R<import('@/types').UserInfo>>('/api/v1/users/me'),
  uploadAvatar: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    // 不手动设 Content-Type（axios 自动带 boundary）
    return planningApi.post<R<string>>('/api/v1/users/avatar', fd);
  },
  /** M5-1：绑定邮箱（注册未填时后补；格式与唯一性由后端校验） */
  updateEmail: (email: string) =>
    planningApi.put<R<void>>('/api/v1/users/email', { email }),
  /** U1：个人中心使用统计（rangeDays：7|30，作用于趋势图/模型用量） */
  usageStats: (rangeDays: 7 | 30) =>
    planningApi.get<R<import('@/types').UsageStats>>('/api/v1/users/me/usage-stats', {
      params: { rangeDays },
    }),
};

// ==================== Itinerary ====================
export const itineraryApi = {
  generate: (data: import('@/types').ItineraryGenerateRequest) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>('/api/v1/itineraries/generate', data),
  /** M6-16：行程流式生成（SSE）——失败由调用方回退 JSON generate */
  generateStream: (
    data: import('@/types').ItineraryGenerateRequest,
    signal: AbortSignal,
    handlers: SseStreamHandlers,
  ) => {
    const headers: Record<string, string> = {};
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) headers.Authorization = `Bearer ${token}`;
      const userId = localStorage.getItem('userId');
      if (userId) headers['X-User-Id'] = userId;
    }
    return consumeSseStream(
      `${PLANNING_BASE}/api/v1/itineraries/generate/stream`,
      { ...data },
      headers,
      signal,
      handlers,
    );
  },
  getById: (id: number) =>
    planningApi.get<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}`),
  list: (userId: number, page = 1, size = 10) =>
    planningApi.get<R<import('@/types').PageResult<import('@/types').ItineraryResponse>>>('/api/v1/itineraries', { params: { userId, page, size } }),
  delete: (id: number) =>
    planningApi.delete<R<void>>(`/api/v1/itineraries/${id}`),
  /** M4-9：断点续跑（仅 FAILED/僵尸 GENERATING 可续；同步等待同 generate） */
  resume: (id: number) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}/resume`),
};

// ==================== Chat ====================
export const chatApi = {
  createSession: (userId: number, title?: string) =>
    planningApi.post<R<string>>('/api/v1/chat/sessions', { userId, title }),
  listSessions: (userId: number) =>
    planningApi.get<R<import('@/types').ChatSession[]>>('/api/v1/chat/sessions', { params: { userId } }),
  getHistory: (sessionId: string) =>
    planningApi.get<R<import('@/types').ChatMessage[]>>(`/api/v1/chat/sessions/${sessionId}/history`),
  /** M4-9：clientMessageId 为消息幂等键——超时/40904 退避重试须携带同键 */
  /** M7 Batch 3：model 可选——请求级模型（null=角色默认） */
  sendMessage: (sessionId: string, message: string, clientMessageId?: string, model?: string) =>
    planningApi.post<R<import('@/types').ChatResponse>>(`/api/v1/chat/sessions/${sessionId}/messages`, {
      message,
      clientMessageId,
      ...(model ? { model } : {}),
    }),
  /** M4-9：显式关闭会话（归档+收口摘要；禁止 beforeunload 触发） */
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
  ) => {
    const headers: Record<string, string> = {};
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) headers.Authorization = `Bearer ${token}`;
      const userId = localStorage.getItem('userId');
      if (userId) headers['X-User-Id'] = userId;
    }
    // P1：断线续传——携带最近收到的事件 id（仅 COMPLETED 重放生效）
    if (lastEventId) headers['Last-Event-ID'] = lastEventId;

    const attempt = (base: string) =>
      consumeSseStream(
        `${base}/api/v1/chat/sessions/${sessionId}/messages/stream`,
        { message, clientMessageId, ...(model ? { model } : {}) },
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

// ==================== Models（M7 Batch 3：模型清单） ====================
export const modelApi = {
  /** 前端可选模型清单（后端仅返回 enabled 且 selectable；embedding/rerank 不可选） */
  list: () =>
    planningApi.get<R<import('@/types').ModelOption[]>>('/api/v1/models'),
};

// ==================== Attractions ====================
export const attractionApi = {
  list: (city?: string, type?: string, page = 1, size = 10) =>
    knowledgeApi.get<R<import('@/types').PageResult<import('@/types').Attraction>>>('/api/v1/attractions', { params: { city, type, page, size } }),
  /** M5-1：全部城市列表（“浏览全部”下拉动态数据源） */
  listCities: () =>
    knowledgeApi.get<R<string[]>>('/api/v1/attractions/cities'),
  getById: (id: number) =>
    knowledgeApi.get<R<import('@/types').Attraction>>(`/api/v1/attractions/${id}`),
  search: (query: string, ragType = 'hybrid', topK = 10) =>
    knowledgeApi.post<R<import('@/types').SearchResult[]>>('/api/v1/attractions/search', { query, ragType, topK }),
};
