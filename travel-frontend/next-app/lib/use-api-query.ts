'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { getErrorMessage } from '@/lib/api';

/**
 * R2/A2（front_design 11 §4-A2，决策 D-01=a）：自研轻量 GET 查询 hook（零依赖）。
 * - deps 变化自动重查；enabled=false 挂起（条件查询）；
 * - 卸载/竞态 cancelled 防护（R9）；refetch 手动重查。
 * 适用：用户面 GET 数据（统计/列表/清单）；写操作与 SSE 仍走页面编排（R4/R5）。
 */
export interface UseApiQueryResult<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
  refetch: () => void;
}

export function useApiQuery<T>(
  fetcher: () => Promise<T>,
  deps: readonly unknown[],
  options?: { enabled?: boolean }
): UseApiQueryResult<T> {
  const enabled = options?.enabled !== false;
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);
  const [tick, setTick] = useState(0);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  useEffect(() => {
    if (!enabled) return undefined;
    let cancelled = false;
    setLoading(true);
    setError(null);
    fetcherRef
      .current()
      .then((d) => {
        if (!cancelled) {
          setData(d);
          setLoading(false);
        }
      })
      .catch((e) => {
        if (!cancelled) {
          setData(null);
          setError(getErrorMessage(e));
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [...deps, enabled, tick]);

  const refetch = useCallback(() => setTick((t) => t + 1), []);

  return { data, loading, error, refetch };
}
