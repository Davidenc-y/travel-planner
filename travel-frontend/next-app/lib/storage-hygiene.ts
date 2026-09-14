/**
 * FE-S2（20260912 前端专项）：存储卫生——跨标签登出广播与启动过期清扫。
 * 不改存储位置（localStorage→cookie 迁移需后端配合，列为远期可选项）。
 * 过期判定复用 lib/token 的 isTokenExpired（F95 同源口径，不重复实现 jwt 解析）。
 */
import { isTokenExpired } from './token';

export const ACCESS_TOKEN_KEY = 'accessToken';
export const REFRESH_TOKEN_KEY = 'refreshToken';

export interface TokenStorage {
  getItem(key: string): string | null;
  removeItem(key: string): void;
}

const defaultStorage = (): TokenStorage | null =>
  typeof window !== 'undefined' ? window.localStorage : null;

export type SweepResult = 'swept' | 'clean';

/**
 * 启动清扫：accessToken 过期且 refreshToken 在场 → 仅移除 access（保留 refresh，
 * 交由既有 401 单飞刷新流恢复）；无 refresh 时不动（沿用 F95 全清口径，由调用方处理）。
 */
export function sweepExpired(storage?: TokenStorage | null): SweepResult {
  const s = storage ?? defaultStorage();
  if (!s) return 'clean';
  const access = s.getItem(ACCESS_TOKEN_KEY);
  if (!access || !isTokenExpired(access)) return 'clean';
  if (!s.getItem(REFRESH_TOKEN_KEY)) return 'clean';
  s.removeItem(ACCESS_TOKEN_KEY);
  return 'swept';
}

/**
 * storage 事件判定：其他标签页移除 refreshToken（newValue 为 null）→ 登出广播；
 * 新增/改值（登录动作）与其他键均不触发。
 */
export function isLogoutBroadcast(e: { key: string | null; newValue: string | null }): boolean {
  return e.key === REFRESH_TOKEN_KEY && e.newValue === null;
}

/** 跨标签登出监听：其他标签页登出（refreshToken 被移除）时回调 cb；返回退订函数。 */
export function watchLogout(cb: () => void): () => void {
  if (typeof window === 'undefined') return () => {};
  const handler = (e: StorageEvent) => {
    if (isLogoutBroadcast(e)) cb();
  };
  window.addEventListener('storage', handler);
  return () => window.removeEventListener('storage', handler);
}
