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

export const PLANNING_BASE = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
export const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';

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

export function createClient(baseURL: string): AxiosInstance {
  const client = axios.create({ baseURL, timeout: 120000 });
  client.interceptors.request.use((config) => {
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      // M16-1：身份仅认 Bearer token（后端已移除 X-User-Id 显式回退）
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
