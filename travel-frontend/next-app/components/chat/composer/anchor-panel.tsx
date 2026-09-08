'use client';

import { MapPin, X } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { useAnchorPanel } from './use-anchor-panel';
import type { AnchorBrief } from '@/types';

/**
 * M23（E1）：输入框左下角锚定圆钮 + 行程选择面板 + 标签行。
 *
 * <p>面板内仅提供"添加/移除锚定"（checkbox 语义）与只读预览，
 * 无任何编辑行程入口（§1.7 禁止编辑约束；行程内容变更唯一通道是 AI REFINE）。</p>
 */
/** M28-10：思考/流式期间锁定（disabled）——防止本轮约束在途被改动造成口径不一致。 */
export function AnchorDotButton({
  count,
  active,
  onClick,
  disabled,
}: {
  count: number;
  active: boolean;
  onClick: () => void;
  disabled?: boolean;
}) {
  return (
    <button
      type="button"
      aria-label={`锚定基准行程${count > 0 ? `（${count}）` : ''}`}
      title="锚定基准行程"
      onClick={onClick}
      disabled={disabled}
      className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full border transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        active
          ? 'border-brand bg-brand/10 text-brand'
          : 'border-line text-ink-faint hover:bg-surface-2'
      }`}
    >
      <MapPin className="h-4 w-4" />
      {count > 0 && (
        <span className="absolute -mt-5 ml-5 rounded-full bg-brand px-1.5 text-[10px] leading-4 text-white">
          {count}
        </span>
      )}
    </button>
  );
}

export function AnchorPanel({
  open,
  onClose,
  anchoredIds,
  onToggle,
  disabled,
}: {
  open: boolean;
  onClose: () => void;
  anchoredIds: number[];
  onToggle: (id: number) => void;
  disabled?: boolean;
}) {
  const { rows, loading, error, page, setPage, totalPages } = useAnchorPanel(open);
  if (!open) return null;
  return (
    <div
      role="dialog"
      aria-label="选择锚定基准行程"
      className="absolute bottom-full left-0 z-30 mb-2 w-96 max-w-[90vw] rounded-xl border border-line bg-surface p-3 shadow-lg"
    >
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-medium">锚定基准行程</span>
        <button type="button" aria-label="关闭" onClick={onClose} className="text-ink-faint hover:text-ink">
          <X className="h-4 w-4" />
        </button>
      </div>
      {loading && <p className="py-4 text-center text-sm text-ink-faint">加载中…</p>}
      {error && <p className="py-4 text-center text-sm text-danger">{error}</p>}
      {!loading && !error && rows.length === 0 && (
        <p className="py-4 text-center text-sm text-ink-faint">暂无历史行程</p>
      )}
      <ul className="max-h-64 space-y-1 overflow-y-auto">
        {rows.map((r: AnchorBrief) => {
          const checked = anchoredIds.includes(r.id);
          return (
            <li key={r.id}>
              <label className={`flex items-center gap-2 rounded-lg px-2 py-1.5 ${disabled ? 'cursor-not-allowed opacity-70' : 'cursor-pointer hover:bg-surface-2'}`}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => onToggle(r.id)}
                  disabled={disabled}
                  className="h-4 w-4 accent-[var(--brand)]"
                />
                <span className="min-w-0 flex-1 truncate text-sm">{r.title}</span>
                {r.destination && <span className="text-xs text-ink-faint">{r.destination}</span>}
                {r.days != null && <span className="text-xs text-ink-faint">{r.days}天</span>}
              </label>
            </li>
          );
        })}
      </ul>
      {totalPages > 1 && (
        <div className="mt-2 flex items-center justify-between text-xs text-ink-faint">
          <Button variant="ghost" size="sm" disabled={page <= 1} onClick={() => setPage(page - 1)}>
            上一页
          </Button>
          <span>
            {page}/{totalPages}
          </span>
          <Button variant="ghost" size="sm" disabled={page >= totalPages} onClick={() => setPage(page + 1)}>
            下一页
          </Button>
        </div>
      )}
      <p className="mt-2 text-xs text-ink-faint">勾选后作为本轮对话的基准规划（仅可锚定一个行程，点其他行程即切换）；只读，不可在此编辑行程。</p>
    </div>
  );
}

/** 锚定标签行（发送区上侧，横向排布；×=移除锚定，点击标签体=打开只读预览由调用方实现）。 */
export function AnchorTags({
  briefs,
  ids,
  onRemove,
  disabled,
}: {
  briefs: Record<number, AnchorBrief>;
  ids: number[];
  onRemove: (id: number) => void;
  disabled?: boolean;
}) {
  if (ids.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5 pb-1.5">
      {ids.map((id) => {
        const b = briefs[id];
        return (
          <span
            key={id}
            className="inline-flex max-w-64 items-center gap-1 rounded-full border border-brand/40 bg-brand/10 px-2 py-0.5 text-xs text-brand"
          >
            <MapPin className="h-3 w-3" />
            <span className="truncate">{b?.title ?? `行程 #${id}`}</span>
            <button
              type="button"
              aria-label={`移除锚定 ${b?.title ?? id}`}
              onClick={() => onRemove(id)}
              disabled={disabled}
              className="ml-0.5 hover:text-danger disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:text-brand"
            >
              <X className="h-3 w-3" />
            </button>
          </span>
        );
      })}
    </div>
  );
}
