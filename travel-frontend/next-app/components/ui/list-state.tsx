'use client';

import type { ReactNode } from 'react';
import { CardGridSkeleton } from './skeleton';
import { EmptyState } from './empty-state';
import { ErrorState } from './error-state';

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
  if (loading) return <CardGridSkeleton count={skeletonCount} />;
  if (error) return <ErrorState message={error} onReset={onRetry} />;
  if (empty) return <EmptyState message={emptyMessage} />;
  return <>{children}</>;
}
