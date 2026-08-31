'use client';

import { useEffect, type RefObject } from 'react';
import { ArrowUp, Square } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/utils';
import { Textarea } from '@/components/ui/input';

/**
 * R2/C-2（front_design 11 §3-C2）：聊天 Composer 纯展示组件。
 * 从 chat/page.tsx 抽出输入区（多行 textarea 自动增高 + 底行：模型选择槽 + 发送/停止圆钮）。
 * 发送编排/幂等/取消逻辑全部留在 page（R4/R5/R6）；本组件只负责呈现与键盘转发：
 * Enter 发送 · Shift+Enter 换行 · Esc 清空草稿（经 onChange('')）。
 * textareaRef 由 page 持有（推荐提示词点击后聚焦输入框）。
 */
export interface ComposerProps {
  value: string;
  onChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  /** 停止按钮可见（思考阶段，M6-49 语义由 page 计算） */
  showStop: boolean;
  canSend: boolean;
  modelSlot: ReactNode;
  textareaRef: RefObject<HTMLTextAreaElement>;
}

export function Composer({ value, onChange, onSend, onStop, showStop, canSend, modelSlot, textareaRef }: ComposerProps) {
  // B3/09 C-01：textarea 自动增高（1~8 行）
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 192)}px`;
  }, [value, textareaRef]);

  return (
    <div className="px-4 pb-4 pt-2">
      <div className="rounded-2xl border border-line bg-surface shadow-1 transition-colors focus-within:border-brand-400">
        <Textarea
          ref={textareaRef}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
              e.preventDefault();
              onSend();
            } else if (e.key === 'Escape') {
              e.preventDefault();
              onChange('');
            }
          }}
          rows={1}
          aria-label="输入消息"
          placeholder="随心输入"
          className="border-0 px-4 pt-3.5 pb-1 min-h-[44px] max-h-40 bg-transparent focus:ring-0 focus:border-0"
        />
        <div className="flex items-center justify-end gap-1 px-3 pb-2.5 pt-1">
          {/* M7 Batch 3 + C1r3：模型选择固定宽度容器（w-48 + 截断）——修复紧凑形态随模型名伸缩的问题 */}
          <div className="w-48 flex-shrink-0">
            {/* M7-7：贴底向上展开；智能默认=不传 model */}
            {modelSlot}
          </div>
          {showStop ? (
            <button
              type="button"
              onClick={onStop}
              title="停止"
              aria-label="停止"
              className="flex h-9 w-9 items-center justify-center rounded-full bg-danger text-white transition-opacity hover:opacity-90 magnetic focus-ring"
            >
              <Square className="h-4 w-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={onSend}
              disabled={!canSend}
              aria-label="发送"
              className={cn(
                'flex h-9 w-9 items-center justify-center rounded-full transition-all magnetic focus-ring',
                canSend
                  ? 'bg-ink text-surface hover:opacity-90 dark:bg-surface dark:text-ink'
                  : 'bg-surface-2 text-ink-faint cursor-not-allowed'
              )}
            >
              <ArrowUp className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
