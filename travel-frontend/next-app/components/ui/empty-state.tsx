'use client';

import type { ReactNode } from 'react';

/**
 * B1（front_design 02 §5.6，升级 F-07）：空态 v2。
 * 图标 + 装饰环 + 提示 + 可选 hint 与 action 槽（"开始规划"等 CTA）。
 * 插画保持内联轻量（不做图片资产，F-04 的品牌插画另批）。
 */
export function EmptyState({
  icon,
  message,
  hint,
  action,
}: {
  icon?: ReactNode;
  message: string;
  hint?: string;
  action?: { label: string; onClick: () => void };
}) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-ink-faint">
      <div className="relative mb-4 flex h-20 w-20 items-center justify-center">
        <span
          aria-hidden
          className="absolute inset-0 rounded-full border-2 border-dashed border-line"
        />
        <span className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-2">
          {icon ?? <InboxDefault />}
        </span>
      </div>
      <p className="text-ink-secondary">{message}</p>
      {hint && <p className="mt-1 text-xs">{hint}</p>}
      {action && (
        <button
          type="button"
          onClick={action.onClick}
          className="mt-4 rounded-lg bg-brand-500 px-4 py-2 text-sm font-medium text-white hover:bg-brand-600 magnetic focus-ring"
        >
          {action.label}
        </button>
      )}
    </div>
  );
}

function InboxDefault() {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.5"
      className="h-6 w-6"
      aria-hidden
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M3 13.5l2.4-7.2A2 2 0 017.3 5h9.4a2 2 0 011.9 1.3L21 13.5V18a2 2 0 01-2 2H5a2 2 0 01-2-2v-4.5z"
      />
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 13.5h5l1 2h6l1-2h5" />
    </svg>
  );
}
