'use client';

import { AlertTriangle, RotateCcw } from 'lucide-react';

export function ErrorState({ message, onReset }: { message: string; onReset?: () => void }) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-slate-400">
      <AlertTriangle className="h-12 w-12 mb-3 opacity-60" />
      <p className="mb-3">{message || '加载失败，请稍后重试'}</p>
      {onReset && (
        <button
          onClick={onReset}
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-500 text-white text-sm hover:bg-brand-600"
        >
          <RotateCcw className="h-4 w-4" /> 重试
        </button>
      )}
    </div>
  );
}
