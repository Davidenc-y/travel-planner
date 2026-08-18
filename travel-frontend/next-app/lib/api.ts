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

const PLANNING_BASE = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';

// ==================== 认证辅助（F87） ====================

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
  if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
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
 * 页面 toast 一律使用本函数，避免重复拼装。
 */
export function getErrorMessage(err: unknown): string {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || '请求失败，请稍后重试';
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

// ==================== Itinerary ====================
export const itineraryApi = {
  generate: (data: import('@/types').ItineraryGenerateRequest) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>('/api/v1/itineraries/generate', data),
  getById: (id: number) =>
    planningApi.get<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}`),
  list: (userId: number, page = 1, size = 10) =>
    planningApi.get<R<import('@/types').PageResult<import('@/types').ItineraryResponse>>>('/api/v1/itineraries', { params: { userId, page, size } }),
  delete: (id: number) =>
    planningApi.delete<R<void>>(`/api/v1/itineraries/${id}`),
};

// ==================== Chat ====================
export const chatApi = {
  createSession: (userId: number, title?: string) =>
    planningApi.post<R<string>>('/api/v1/chat/sessions', { userId, title }),
  listSessions: (userId: number) =>
    planningApi.get<R<import('@/types').ChatSession[]>>('/api/v1/chat/sessions', { params: { userId } }),
  getHistory: (sessionId: string) =>
    planningApi.get<R<import('@/types').ChatMessage[]>>(`/api/v1/chat/sessions/${sessionId}/history`),
  sendMessage: (sessionId: string, message: string) =>
    planningApi.post<R<import('@/types').ChatResponse>>(`/api/v1/chat/sessions/${sessionId}/messages`, { message }),
  /**
   * F92：流式聊天预留（SSE）。当前后端为一次性 JSON 响应，本方法返回原生 fetch Response；
   * 后端支持 SSE 后，消费 response.body 的 ReadableStream 即可实现打字机效果。
   */
  sendMessageStream: (sessionId: string, message: string, signal?: AbortSignal) =>
    fetch(`${PLANNING_BASE}/api/v1/chat/sessions/${sessionId}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(typeof window !== 'undefined' && localStorage.getItem('accessToken')
          ? { Authorization: `Bearer ${localStorage.getItem('accessToken')}` }
          : {}),
        ...(typeof window !== 'undefined' && localStorage.getItem('userId')
          ? { 'X-User-Id': localStorage.getItem('userId')! }
          : {}),
      },
      body: JSON.stringify({ message }),
      signal,
    }),
};

// ==================== Attractions ====================
export const attractionApi = {
  list: (city?: string, type?: string, page = 1, size = 10) =>
    knowledgeApi.get<R<import('@/types').PageResult<import('@/types').Attraction>>>('/api/v1/attractions', { params: { city, type, page, size } }),
  getById: (id: number) =>
    knowledgeApi.get<R<import('@/types').Attraction>>(`/api/v1/attractions/${id}`),
  search: (query: string, ragType = 'hybrid', topK = 10) =>
    knowledgeApi.post<R<import('@/types').SearchResult[]>>('/api/v1/attractions/search', { query, ragType, topK }),
};
