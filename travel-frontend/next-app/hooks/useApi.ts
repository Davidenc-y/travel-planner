'use client';

import { useCallback, useEffect, useRef, useState } from 'react';

interface State<T> {
  data: T | null;
  loading: boolean;
  error: Error | null;
}

const cache = new Map<string, unknown>();

/**
 * 轻量 SWR 风格 hook（F91）：同 key 并发去重 + 内存缓存。
 * GET 类数据拉取统一走这里，页面不再各自维护 loading/error。
 */
export function useApi<T>(key: string, fetcher: () => Promise<T>, options?: { enabled?: boolean }) {
  const [state, setState] = useState<State<T>>(() => ({
    data: (cache.get(key) as T | undefined) ?? null,
    loading: false,
    error: null,
  }));
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;

  const load = useCallback(async () => {
    const cached = cache.get(key) as T | undefined;
    if (cached !== undefined) {
      setState({ data: cached, loading: false, error: null });
      return;
    }
    setState((s) => ({ ...s, loading: true, error: null }));
    try {
      const data = await fetcherRef.current();
      cache.set(key, data);
      setState({ data, loading: false, error: null });
    } catch (e) {
      setState({ data: null, loading: false, error: e as Error });
    }
  }, [key]);

  useEffect(() => {
    if (options?.enabled === false) return;
    void load();
  }, [load, options?.enabled]);

  return { ...state, reload: load };
}
