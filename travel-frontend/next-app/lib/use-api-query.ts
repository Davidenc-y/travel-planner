'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '@/lib/api';

/**
 * R2/A2（front_design 11 §4-A2，决策 D-01=a）：自研轻量 GET 查询 hook（零依赖）。
 * - deps 变化自动重查；enabled=false 挂起（条件查询）；
 * - 卸载/竞态 cancelled 防护（R9）；refetch 手动重查。
 * - FE-P3.1（20260912 前端专项）复取治理（stale-while-revalidate lite）：结果新增
 *   freshAt（最近成功取数时刻）；传入 cacheKey 后，重挂载/键切换与窗口重聚焦时若
 *   缓存 age < staleMs（默认 30s）直接用缓存（不闪 loading）并后台静默刷新——静默
 *   失败保持旧数据、不置 error（与预取 .catch(() => {}) 同口径）；age ≥ staleMs 走
 *   正常 loading 重查。未传 cacheKey 时行为与现状逐字节一致（无跨挂载缓存、无聚焦
 *   重查）。cacheKey 必须随业务参数变化（如 'itinerary:1:8'），否则键切换会误用旧键
 *   数据。
 * 适用：用户面 GET 数据（统计/列表/清单）；写操作与 SSE 仍走页面编排（R4/R5）。
 */
export interface UseApiQueryResult<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  refetch: () => void;
  freshAt: number | null;
}

export interface UseApiQueryOptions {
  enabled?: boolean;
  /** FE-P3.1：静默刷新窗口（ms），默认 30_000；仅传入 cacheKey 时生效 */
  staleMs?: number;
  /** FE-P3.1：跨挂载缓存键；必须随业务参数变化；不传则维持现状行为 */
  cacheKey?: string;
}

interface SwrEntry {
  data: unknown;
  at: number;
}

/** FE-P3.1：跨挂载 SWR-lite 缓存（模块级，仅 cacheKey 使用者受益；TTL 惰性，不主动清扫） */
const swrCache = new Map<string, SwrEntry>();

export function useApiQuery<T>(
  fetcher: () => Promise<T>,
  deps: readonly unknown[],
  options?: UseApiQueryOptions
): UseApiQueryResult<T> {
  const enabled = options?.enabled !== false;
  const staleMs = options?.staleMs ?? 30_000;
  const cacheKey = options?.cacheKey;

  // 首渲染即从缓存水合（重挂载"直接用缓存"，不闪 loading）
  const cachedAtMount = cacheKey ? swrCache.get(cacheKey) : undefined;
  const freshAtMount = cachedAtMount && Date.now() - cachedAtMount.at < staleMs ? cachedAtMount : undefined;

  const [data, setData] = useState<T | null>(() => (freshAtMount ? (freshAtMount.data as T) : null));
  const [freshAt, setFreshAt] = useState<number | null>(() => freshAtMount?.at ?? null);
  const [loading, setLoading] = useState(enabled && !freshAtMount);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;

    const cached = cacheKey ? swrCache.get(cacheKey) : undefined;
    const silent = Boolean(cached && Date.now() - cached.at < staleMs);

    if (!silent) {
      setLoading(true);
    }
    setError(null);
    fetcherRef
      .current()
      .then((d) => {
        if (cancelled) return;
        setData(d);
        setLoading(false);
        setFreshAt(Date.now());
        if (cacheKey) swrCache.set(cacheKey, { data: d, at: Date.now() });
      })
      .catch((e) => {
        if (cancelled) return;
        if (silent) {
          // 静默刷新失败：保持旧数据，不打断展示（与预取 .catch(() => {}) 同口径）
          setLoading(false);
          return;
        }
        setData(null);
        setError(getErrorMessage(e));
        setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [...deps, enabled, tick]);

  // FE-P3.1：重聚焦治理——新鲜缓存静默刷新（不闪 loading），过期缓存正常重查；
  // 仅 cacheKey 使用者注册（未传 cacheKey 时与现状一致：无聚焦重查）。
  useEffect(() => {
    if (!enabled || !cacheKey || typeof window === 'undefined') return undefined;
    let cancelled = false;

    const revalidate = () => {
      const cached = swrCache.get(cacheKey);
      if (!cached) return;
      if (Date.now() - cached.at < staleMs) {
        fetcherRef
          .current()
          .then((d) => {
            if (cancelled) return;
            setData(d);
            setFreshAt(Date.now());
            swrCache.set(cacheKey, { data: d, at: Date.now() });
          })
          .catch(() => {});
      } else {
        setTick((t) => t + 1); // 过期 → 主 effect 走正常 loading 重查
      }
    };

    window.addEventListener('focus', revalidate);
    return () => {
      cancelled = true;
      window.removeEventListener('focus', revalidate);
    };
  }, [enabled, cacheKey, staleMs]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);

  return { data, loading, error, refetch, freshAt };
}
