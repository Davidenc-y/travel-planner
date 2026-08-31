'use client';

import { useEffect } from 'react';
import { AlertTriangle, RotateCcw } from 'lucide-react';

/**
 * R2/A1（front_design 11 §4-A1）：App Router 路由级错误边界展示组件。
 * 各路由段 error.tsx 复用；reset 由 Next 传入（重新渲染该段）。
 */
export function RouteError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // 供观测/上报：路由级渲染错误（digest 为 Next 服务端错误关联 ID）
    console.error('[RouteError]', error.message, error.digest ?? '');
  }, [error]);
  return (
    <div className="flex flex-col items-center justify-center py-20 text-ink-faint">
      <AlertTriangle className="h-12 w-12 mb-3 opacity-60" />
      <p className="mb-1 text-ink-secondary">页面渲染出现问题</p>
      <p className="mb-4 text-xs">{error.message || '未知错误'}</p>
      <button
        type="button"
        onClick={reset}
        className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-500 text-white text-sm hover:bg-brand-600 focus-ring"
      >
        <RotateCcw className="h-4 w-4" /> 重试
      </button>
    </div>
  );
}
