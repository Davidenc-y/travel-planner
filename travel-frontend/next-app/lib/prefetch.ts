/**
 * 跨页面数据预取缓存（F102）：进入任意页面时由 PrefetchProvider 后台异步预取
 * 其他页面的列表数据；目标页面挂载时先取缓存（取走即删），避免切换卡顿。
 */

const cache = new Map<string, unknown>();

export function setPrefetch(key: string, data: unknown): void {
  cache.set(key, data);
}

export function takePrefetch<T>(key: string): T | null {
  if (!cache.has(key)) return null;
  const value = cache.get(key) as T;
  cache.delete(key);
  return value;
}
