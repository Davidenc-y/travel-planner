'use client';

import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronLeft, ChevronRight } from 'lucide-react';
import { cn } from '@/lib/utils';

export interface PagedOption {
  value: string;
  label?: string;
}

interface PagedOptionsProps {
  options: PagedOption[];
  /** 已选项（多选为数组；单选传单元素数组） */
  selected: string[];
  onToggle: (value: string) => void;
  placeholder?: string;
  defaultPageSize?: number;
  pageSizeOptions?: number[];
  multiple?: boolean;
  /** M7-7：面板向上展开（底部输入区/贴边场景防溢出视口） */
  dropUp?: boolean;
}

/**
 * 分页下拉选项面板（F99）：承载未来真实爬虫数据的大选项集。
 * 支持：翻页、每页条数可选（默认 10）、多选/单选（单选再次点击可取消）。
 */
export function PagedOptions({
  options,
  selected,
  onToggle,
  placeholder = '请选择',
  defaultPageSize = 10,
  pageSizeOptions = [10, 20, 50],
  multiple = true,
  dropUp = false,
}: PagedOptionsProps) {
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(defaultPageSize);
  const rootRef = useRef<HTMLDivElement>(null);

  // F103：点击选项框外部任意位置自动收起
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const totalPages = Math.max(1, Math.ceil(options.length / pageSize));
  const currentPage = Math.min(page, totalPages);
  const slice = options.slice((currentPage - 1) * pageSize, currentPage * pageSize);

  const changePageSize = (size: number) => {
    setPageSize(size);
    setPage(1);
  };

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className={cn(
          'w-full flex items-center justify-between gap-2 px-3 py-2 rounded-lg border text-sm transition-colors',
          'border-slate-200 dark:border-slate-700 bg-transparent hover:border-brand-500'
        )}
      >
        <span className="flex min-w-0 flex-1 flex-wrap items-center gap-1">
          {selected.map((v) => {
            const opt = options.find((o) => o.value === v);
            return (
              <span
                key={v}
                className="group inline-flex items-center gap-1 rounded-full bg-brand-50 dark:bg-brand-900/30 px-2 py-0.5 text-xs text-brand-600 dark:text-brand-300"
              >
                {opt?.label ?? v}
                <button
                  type="button"
                  aria-label={`取消选择 ${opt?.label ?? v}`}
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggle(v);
                  }}
                  className="hidden h-3.5 w-3.5 items-center justify-center rounded-full text-brand-500 hover:bg-red-100 hover:text-red-500 group-hover:inline-flex"
                >
                  ×
                </button>
              </span>
            );
          })}
          {selected.length === 0 && <span className="truncate">{placeholder}</span>}
        </span>
        <ChevronDown className={cn('h-4 w-4 shrink-0 transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        // F100：下拉面板改不透明（原 glass 半透明透出背景，妨碍选择）
        <div className={cn(
          'absolute z-30 w-full rounded-xl border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 p-3 shadow-xl',
          dropUp ? 'bottom-full mb-1' : 'top-full mt-1'
        )}>
          {/* 顶部：总数 + 每页条数 */}
          <div className="flex items-center justify-between mb-2 text-xs text-slate-500 dark:text-slate-400">
            <span>共 {options.length} 项</span>
            <label className="flex items-center gap-1">
              每页
              <select
                value={pageSize}
                onChange={(e) => changePageSize(Number(e.target.value))}
                className="px-1.5 py-0.5 rounded border border-slate-200 dark:border-slate-700 bg-transparent text-xs"
              >
                {pageSizeOptions.map((n) => (
                  <option key={n} value={n}>{n} 条</option>
                ))}
              </select>
            </label>
          </div>

          {/* 选项列表 */}
          <div className={cn('grid gap-1 max-h-56 overflow-y-auto', multiple ? 'grid-cols-2' : 'grid-cols-1')}>
            {slice.map((opt) => {
              const checked = selected.includes(opt.value);
              return (
                <label
                  key={opt.value}
                  onClick={multiple ? undefined : () => onToggle(opt.value)}
                  className={cn(
                    'flex items-center gap-2 px-2 py-1.5 rounded-lg text-sm cursor-pointer border transition-colors',
                    checked
                      ? 'border-brand-500 bg-brand-50 dark:bg-brand-900/30'
                      : 'border-transparent hover:bg-slate-100 dark:hover:bg-slate-800'
                  )}
                >
                  {multiple ? (
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => onToggle(opt.value)}
                      className="accent-brand-500"
                    />
                  ) : (
                    <span
                      className={cn(
                        'flex h-4 w-4 items-center justify-center rounded-full border text-[10px]',
                        checked ? 'border-brand-500 bg-brand-500 text-white' : 'border-slate-300'
                      )}
                    >
                      {checked ? '✓' : ''}
                    </span>
                  )}
                  <span className="truncate">{opt.label ?? opt.value}</span>
                </label>
              );
            })}
          </div>

          {/* 分页 */}
          <div className="flex items-center justify-between mt-2 text-xs">
            <button
              type="button"
              disabled={currentPage <= 1}
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              className="flex items-center gap-0.5 px-2 py-1 rounded bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
            >
              <ChevronLeft className="h-3 w-3" /> 上一页
            </button>
            <span className="text-slate-500">{currentPage} / {totalPages}</span>
            <button
              type="button"
              disabled={currentPage >= totalPages}
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              className="flex items-center gap-0.5 px-2 py-1 rounded bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
            >
              下一页 <ChevronRight className="h-3 w-3" />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * 配置式分页下拉（F102）：通过 multiple 变量配置多选/单选；
 * 已选项以标签展示在触发框内，悬浮时右上角 × 可取消。
 */
export function PagedSelect(props: PagedOptionsProps) {
  return <PagedOptions {...props} />;
}

/** 多选下拉（兴趣等） */
export function PagedMultiSelect(props: Omit<PagedOptionsProps, 'multiple' | 'selected' | 'onToggle'> & {
  selected: string[];
  onToggle: (value: string) => void;
}) {
  return <PagedOptions {...props} multiple />;
}

/** 单选下拉（出行人员等；再次点击已选项可取消） */
export function PagedSingleSelect(props: Omit<PagedOptionsProps, 'multiple' | 'selected' | 'onToggle'> & {
  value?: string;
  onChange: (value: string | undefined) => void;
}) {
  const { value, onChange, ...rest } = props;
  return (
    <PagedOptions
      {...rest}
      multiple={false}
      selected={value ? [value] : []}
      onToggle={(v) => onChange(value === v ? undefined : v)}
    />
  );
}
