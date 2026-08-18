'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth-context';
import { itineraryApi, chatApi, attractionApi } from '@/lib/api';
import { setPrefetch } from '@/lib/prefetch';

/**
 * 全局预取（F102）：
 *  - 路由级：router.prefetch 提前拉取各页面 JS chunk；
 *  - 数据级：后台异步预取行程列表/会话列表/景点列表写入预取缓存；
 *  - 线程/生命周期：组件卸载或登录态变化时置 cancelled，预取结果不再写缓存
 *    （等价于正确关闭后台异步任务，避免无意义写入与泄漏）。
 */
export function PrefetchProvider() {
  const router = useRouter();
  const { isAuthenticated, userId } = useAuth();

  useEffect(() => {
    // 路由级预取（Next 内部处理请求生命周期）
    router.prefetch('/plan');
    router.prefetch('/itinerary');
    router.prefetch('/chat');
    router.prefetch('/attractions');
    router.prefetch('/profile');

    if (!isAuthenticated || userId == null) return;

    let cancelled = false;

    itineraryApi.list(userId, 1, 8)
      .then((r) => {
        if (!cancelled) setPrefetch('itinerary:1:8', r.data.data);
      })
      .catch(() => {});

    chatApi.listSessions(userId)
      .then((r) => {
        if (!cancelled) setPrefetch('chat:sessions', r.data.data);
      })
      .catch(() => {});

    attractionApi.list(undefined, undefined, 1, 12)
      .then((r) => {
        if (!cancelled) setPrefetch('attractions:1:12', r.data.data);
      })
      .catch(() => {});

    return () => {
      cancelled = true;
    };
  }, [isAuthenticated, userId, router]);

  return null;
}
