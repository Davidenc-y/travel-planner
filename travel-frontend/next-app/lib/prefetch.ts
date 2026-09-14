/**
 * 跨页面数据预取缓存（F102）：进入任意页面时由 PrefetchProvider 后台异步预取
 * 其他页面的列表数据；目标页面挂载时先取缓存（取走即删），避免切换卡顿。
 * FE-P1a（20260912 前端专项）：写入带 TTL（默认 60s）惰性过期；新增 peekPrefetch
 * 只读访问器（不删，供调试/观测）。takePrefetch 取走即删契约不变，既有消费端零改动。
 */

const cache = new Map<string, { value: unknown; expiresAt: number }>();

function getLiveEntry(key: string): { value: unknown } | null {
  const entry = cache.get(key);
  if (!entry) return null;
  if (Date.now() >= entry.expiresAt) {
    cache.delete(key);
    return null;
  }
  return entry;
}

/**
 * 写入预取缓存；ttlMs 后过期，惰性清除（下次读写该键时剔除），默认 60_000。
 */
export function setPrefetch(key: string, data: unknown, ttlMs = 60_000): void {
  cache.set(key, { value: data, expiresAt: Date.now() + ttlMs });
}

/** 取走即删（既有契约不变）；过期键视为不存在并就地清除。 */
export function takePrefetch<T>(key: string): T | null {
  const entry = getLiveEntry(key);
  if (!entry) return null;
  cache.delete(key);
  return entry.value as T;
}

/** 只读访问（不删，供调试/观测）；过期键就地清除并返回 null。 */
export function peekPrefetch<T>(key: string): T | null {
  const entry = getLiveEntry(key);
  return entry ? (entry.value as T) : null;
}
