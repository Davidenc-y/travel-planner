'use client';

import { ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * B1（front_design 02 §5.5，收敛 F-14）：统一分页。
 * 迁移对象：行程列表（带每页条数）与景点浏览（不带）两处手写分页。
 */
export interface PaginationProps {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  disabled?: boolean;
  pageSize?: number;
  pageSizeOptions?: number[];
  onPageSizeChange?: (size: number) => void;
  className?: string;
}

export function Pagination({
  page,
  totalPages,
  onChange,
  disabled,
  pageSize,
  pageSizeOptions,
  onPageSizeChange,
  className,
}: PaginationProps) {
  if (totalPages <= 1 && !pageSizeOptions) return null;
  return (
    <div className={cn('flex items-center justify-center gap-2 mt-6', className)}>
      {pageSizeOptions && onPageSizeChange && (
        <label className="mr-2 flex items-center gap-1 text-sm text-ink-secondary">
          每页
          <select
            value={pageSize}
            onChange={(e) => onPageSizeChange(Number(e.target.value))}
            className="rounded border border-line bg-transparent px-1.5 py-1 text-sm"
          >
            {pageSizeOptions.map((n) => (
              <option key={n} value={n}>
                {n} 条
              </option>
            ))}
          </select>
        </label>
      )}
      <button
        type="button"
        disabled={page <= 1 || disabled}
        onClick={() => onChange(page - 1)}
        className="flex items-center gap-0.5 rounded-lg bg-surface-2 px-3 py-1.5 text-sm disabled:opacity-40 focus-ring"
      >
        <ChevronLeft className="h-3.5 w-3.5" /> 上一页
      </button>
      <span className="text-sm text-ink-secondary">
        {page} / {totalPages}
      </span>
      <button
        type="button"
        disabled={page >= totalPages || disabled}
        onClick={() => onChange(page + 1)}
        className="flex items-center gap-0.5 rounded-lg bg-surface-2 px-3 py-1.5 text-sm disabled:opacity-40 focus-ring"
      >
        下一页 <ChevronRight className="h-3.5 w-3.5" />
      </button>
    </div>
  );
}
