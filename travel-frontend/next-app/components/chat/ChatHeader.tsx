'use client';

import { Loader2, PanelLeft } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * R2/C-2（front_design 11 §3-C2）：会话头部纯展示组件。
 * 窄屏抽屉按钮 + 当前会话标题 + 在途 spinner；模型徽标已移至 Composer（C1）。
 */
export function ChatHeader({
  title,
  streaming,
  onOpenDrawer,
}: {
  title: string;
  streaming: boolean;
  onOpenDrawer: () => void;
}) {
  return (
    <div className="flex items-center gap-2 border-b border-line px-4 py-2.5">
      <button
        type="button"
        onClick={onOpenDrawer}
        aria-label="打开会话列表"
        className="md:hidden rounded-lg p-1.5 text-ink-secondary hover:bg-surface-2 focus-ring"
      >
        <PanelLeft className="h-5 w-5" />
      </button>
      <h2 className={cn('flex-1 truncate text-sm font-semibold')}>{title}</h2>
      {streaming && <Loader2 className="h-4 w-4 animate-spin text-brand-500" aria-label="生成中" />}
    </div>
  );
}
