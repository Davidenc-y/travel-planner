import type { R } from '@/types';
import { planningApi } from './http';

// ==================== Auth ====================
export const authApi = {
  login: (username: string, password: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/login', { username, password }),
  register: (username: string, password: string, email?: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/register', { username, password, email }),
  /** F87：退出登录——通知后端注销 Redis refreshToken（best-effort，失败仅清本地） */
  logout: () => planningApi.post<R<void>>('/api/v1/auth/logout'),
};
