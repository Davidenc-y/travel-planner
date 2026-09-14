'use client';

import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { CardGridSkeleton } from './skeleton';
import { EmptyState } from './empty-state';
import { ErrorState } from './error-state';
import { minDisplay } from '@/lib/motion-tokens';

interface ListStateProps {
  loading: boolean;
  error: string | null;
  empty: boolean;
  emptyMessage?: string;
  onRetry?: () => void;
  skeletonCount?: number;
  children: ReactNode;
}

/**
 * M3-21：列表三态统一组件（Skeleton/Error/Empty → children），
 * 消除各列表页重复的 loading/error/empty 样板（P2-15）。
 */
export function ListState({
  loading,
  error,
  empty,
  emptyMessage = '暂无数据',
  onRetry,
  skeletonCount = 6,
  children,
}: ListStateProps) {
  // FE-A1：骨架最少展示 300ms（防闪跳）——loading 翻 false 时不足 minDisplay
  // 则延迟切换到内容，期间保持骨架渲染。
  const loadingSinceRef = useRef<number | null>(null);
  const [holdSkeleton, setHoldSkeleton] = useState(false);

  useEffect(() => {
    if (loading) {
      loadingSinceRef.current = Date.now();
      setHoldSkeleton(false);
      return undefined;
    }
    const since = loadingSinceRef.current;
    if (since == null) return undefined;
    const remain = minDisplay(Date.now() - since);
    if (remain <= 0) return undefined;
    setHoldSkeleton(true);
    const timer = setTimeout(() => setHoldSkeleton(false), remain);
    return () => clearTimeout(timer);
  }, [loading]);

  const effectiveLoading = loading || holdSkeleton;

  if (effectiveLoading) return <CardGridSkeleton count={skeletonCount} />;
  if (error) return <ErrorState message={error} onReset={onRetry} />;
  if (empty) return <EmptyState message={emptyMessage} />;
  return <>{children}</>;
}
