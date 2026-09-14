'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth-context';
import { itineraryApi, chatApi, attractionApi } from '@/lib/api';
import { setPrefetch } from '@/lib/prefetch';
import { keysFor } from '@/lib/prefetch-map';

/**
 * 全局预取（F102；FE-P1b 空闲调度接线）：
 *  - 路由级：router.prefetch 提前拉取各页面 JS chunk（行为不变）；
 *  - 数据级：requestIdleCallback（fallback setTimeout 1500ms）空闲时按当前 pathname
 *    查 lib/prefetch-map 决定预取集，结果经 setPrefetch 写入缓存（TTL 60s 惰性过期）；
 *  - 去重：同键在途 Map<key,Promise>，重复调度不并发重复请求；
 *  - 线程/生命周期：组件卸载或登录态变化时取消空闲回调并置 cancelled，在途结果不再
 *    写缓存（等价于正确关闭后台异步任务，避免无意义写入与泄漏）。
 *  消费端 takePrefetch 取走即删契约零改动。
 */

/** 预取键 → 数据拉取器（与既有 Producer 调用签名一致，集中收拢三处 setPrefetch） */
const prefetchFetchers: Record<string, () => Promise<unknown>> = {
  'itinerary:1:8': () => itineraryApi.list(1, 8).then((r) => r.data.data),
  'chat:sessions': () => chatApi.listSessions().then((r) => r.data.data),
  'attractions:1:12': () => attractionApi.list(undefined, undefined, 1, 12).then((r) => r.data.data),
};

/** 同键在途去重：进行中的预取 Promise（settling 后自清，仅同键新请求可重入） */
const inflight = new Map<string, Promise<unknown>>();

function scheduleIdle(callback: () => void): number {
  if (typeof window !== 'undefined' && typeof window.requestIdleCallback === 'function') {
    return window.requestIdleCallback(callback);
  }
  return window.setTimeout(callback, 1500);
}

function cancelIdle(handle: number): void {
  if (typeof window !== 'undefined' && typeof window.cancelIdleCallback === 'function') {
    window.cancelIdleCallback(handle);
  } else {
    window.clearTimeout(handle);
  }
}

export function PrefetchProvider() {
  const router = useRouter();
  const pathname = usePathname();
  const { isAuthenticated, userId } = useAuth();

  useEffect(() => {
    // 路由级预取（Next 内部处理请求生命周期）
    router.prefetch('/itinerary');
    router.prefetch('/chat');
    router.prefetch('/attractions');
    router.prefetch('/profile');
  }, [router]);

  useEffect(() => {
    if (!isAuthenticated || userId == null) return;

    let cancelled = false;

    const run = () => {
      for (const key of keysFor(pathname)) {
        const fetcher = prefetchFetchers[key];
        if (!fetcher || inflight.has(key)) continue;
        const p = fetcher()
          .then((data) => {
            if (!cancelled) setPrefetch(key, data);
          })
          .catch(() => {})
          .finally(() => {
            if (inflight.get(key) === p) inflight.delete(key);
          });
        inflight.set(key, p);
      }
    };

    const handle = scheduleIdle(run);
    return () => {
      cancelled = true;
      cancelIdle(handle);
    };
  }, [isAuthenticated, userId, pathname]);

  return null;
}
