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

import axios, { AxiosInstance, AxiosResponse, InternalAxiosRequestConfig } from 'axios';
import type { AuthResponse, R } from '@/types';
import { backoffDelay } from '@/lib/backoff';

export const PLANNING_BASE = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
export const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';

// ==================== GET 在途去重（FE-P2） ====================
// 仅 GET 且"method+url+序列化 params"同键的并发请求共享同一在途 Promise；响应以浅拷贝
// 分发给各等待者。错误不缓存（键即释放）；非 GET 或携带自定义头（Authorization/Accept/
// Content-Type 之外）的请求不去重；在途键上限 32，LRU 淘汰。开关 NEXT_PUBLIC_HTTP_DEDUPE
// （默认开，置 'false' 关闭）。
//
// 响应对象不可变性约定：去重分发与共享均为浅拷贝（{...res}），data 载荷仍为同引用——
// 消费端不得就地修改响应对象或 data（如需变更请自行克隆），否则可能污染并发等待者。
const HTTP_DEDUPE_ENABLED = process.env.NEXT_PUBLIC_HTTP_DEDUPE !== 'false';
const DEDUPE_MAX_KEYS = 32;
const DEDUPE_HIT = '__dedupe_hit__';
const BENIGN_HEADER_KEYS = new Set(['authorization', 'accept', 'content-type']);

interface DedupeMeta {
  key: string;
  resolveShared: (res: AxiosResponse) => void;
  rejectShared: (err: unknown) => void;
}

/** params 确定性序列化（键排序），保证同参不同序生成同键 */
function stableParamsValue(params: unknown): string {
  if (params == null) return '';
  if (typeof URLSearchParams !== 'undefined' && params instanceof URLSearchParams) {
    return params.toString();
  }
  if (typeof params !== 'object') return String(params);
  return JSON.stringify(params, (_k, v) => {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      return Object.keys(v as Record<string, unknown>)
        .sort()
        .reduce<Record<string, unknown>>((acc, key) => {
          acc[key] = (v as Record<string, unknown>)[key];
          return acc;
        }, {});
    }
    return v;
  });
}

function dedupeKey(config: InternalAxiosRequestConfig): string {
  return `${(config.method || 'get').toUpperCase()} ${config.url || ''} ${stableParamsValue(config.params)}`;
}

function flattenHeaders(headers: unknown): Record<string, unknown> {
  if (!headers) return {};
  const h = headers as { toJSON?: () => Record<string, unknown> };
  return typeof h.toJSON === 'function' ? h.toJSON() : (headers as Record<string, unknown>);
}

/** 去重资格：仅 GET，且未携带白名单（Authorization/Accept/Content-Type）之外的自定义头 */
function isDedupeEligible(config: InternalAxiosRequestConfig): boolean {
  if ((config.method || '').toUpperCase() !== 'GET') return false;
  const flat = flattenHeaders(config.headers);
  return !Object.keys(flat).some((k) => !BENIGN_HEADER_KEYS.has(k.toLowerCase()));
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

export function createClient(baseURL: string): AxiosInstance {
  const client = axios.create({ baseURL, timeout: 120000 });

  // ---- FE-P2：GET 在途去重拦截器（先注册：响应链先于 401 处理器执行）。
  // 命中在途键的请求在请求链抛 sentinel，由响应错误拦截器回收并复用同一 Promise。
  const inflightGets = new Map<string, { promise: Promise<AxiosResponse> }>();
  const dedupeMetaByConfig = new WeakMap<InternalAxiosRequestConfig, DedupeMeta>();

  client.interceptors.request.use((config) => {
    if (!HTTP_DEDUPE_ENABLED || !isDedupeEligible(config)) return config;
    const key = dedupeKey(config);
    const existing = inflightGets.get(key);
    if (existing) {
      inflightGets.delete(key);
      inflightGets.set(key, existing); // LRU touch
      throw { [DEDUPE_HIT]: key };
    }
    let resolveShared!: (res: AxiosResponse) => void;
    let rejectShared!: (err: unknown) => void;
    const promise = new Promise<AxiosResponse>((resolve, reject) => {
      resolveShared = resolve;
      rejectShared = reject;
    });
    // 二次审批修复（2026-09-14）：单调用者错误路径下 rejectShared 会打到无消费者的
    // 共享 Promise（unhandled rejection 噪音）；挂一个 no-op catch 标记已处理，
    // 真实等待者仍各自经 promise 感知拒绝（附加 handler 不影响他人订阅）。
    promise.catch(() => {});
    if (inflightGets.size >= DEDUPE_MAX_KEYS) {
      const oldest = inflightGets.keys().next().value;
      if (oldest !== undefined) inflightGets.delete(oldest);
    }
    inflightGets.set(key, { promise });
    dedupeMetaByConfig.set(config, { key, resolveShared, rejectShared });
    return config;
  });

  client.interceptors.response.use(
    (res) => {
      const meta = dedupeMetaByConfig.get(res.config);
      if (!meta) return res;
      dedupeMetaByConfig.delete(res.config);
      inflightGets.delete(meta.key);
      meta.resolveShared({ ...res }); // 共享者独立浅拷贝（响应对象不可变性约定见文件头注释）
      return { ...res }; // 发起者亦为独立浅拷贝
    },
    (error) => {
      const hitKey = (error as Record<string, unknown> | null)?.[DEDUPE_HIT];
      if (typeof hitKey === 'string') {
        const entry = inflightGets.get(hitKey);
        return entry ? entry.promise : Promise.reject(error);
      }
      const errConfig = error?.config as InternalAxiosRequestConfig | undefined;
      const meta = errConfig ? dedupeMetaByConfig.get(errConfig) : undefined;
      if (meta && errConfig) {
        dedupeMetaByConfig.delete(errConfig);
        inflightGets.delete(meta.key);
        meta.rejectShared(error); // 错误穿透全部等待者；键即释放（错误不缓存）
      }
      return Promise.reject(error);
    }
  );

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

  // ---- FE-C2：5xx/网络错误指数退避重试（上限 2 次；401 单飞刷新既有语义优先且不走此环）。
  // 二次审批裁决（2026-09-14，FE-C2-fix）：自动重试收窄为 GET-only——非 GET（如 POST
  // /itineraries/generate 创建行程、POST /resume）在网络错误下重放存在重复提交风险；
  // 已知幂等的写操作未来可经请求级 header 'X-Retry-Idempotent: true' 显式 opt-in。
  client.interceptors.response.use(
    (res) => res,
    (error) => {
      const status = error?.response?.status;
      if (status === 401) return Promise.reject(error); // 交给上一环单飞刷新/清凭据
      if (error?.code === 'ERR_CANCELED' || isAbortError(error)) return Promise.reject(error);
      const cfg = error?.config as (InternalAxiosRequestConfig & { _netRetries?: number }) | undefined;
      if (!cfg) return Promise.reject(error);
      const method = (cfg.method || 'get').toLowerCase();
      const idempotentOptIn = cfg.headers?.['X-Retry-Idempotent'] === 'true';
      if (method !== 'get' && !idempotentOptIn) return Promise.reject(error);
      const retryable = status == null || status >= 500; // 网络错误（无响应）或 5xx
      if (!retryable) return Promise.reject(error);
      const retries = cfg._netRetries ?? 0;
      if (retries >= 2) return Promise.reject(error);
      cfg._netRetries = retries + 1;
      const delay = backoffDelay(retries); // 首次 0ms 立即，第二次 300±j
      return new Promise((resolve, reject) => {
        setTimeout(() => {
          client(cfg).then(resolve, reject);
        }, delay);
      });
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
