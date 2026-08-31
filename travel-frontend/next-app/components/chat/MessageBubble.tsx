'use client';

import { memo, useEffect, useState } from 'react';
import Link from 'next/link';
import { Check, ChevronRight, Copy, Loader2, Pencil, Plane, RotateCcw, X } from 'lucide-react';
import { toast } from 'sonner';
import type { ChatMessage, MessageProcess } from '@/types';
import { cn } from '@/lib/utils';
import { buildItineraryUrl } from '@/lib/url-guard';
import { STREAM_MARKDOWN_PREF_KEY } from '@/lib/constants';
import { copyText } from '@/lib/clipboard';
import { formatClockTime, formatDurationShort } from '@/lib/time-format';
import { ChatMessageContent } from '@/components/feature/chat-message-content';
import { useThrottledValue } from '@/lib/use-throttled-value';

// M6-36/R2：时间戳与时长格式化统一走 lib/time-format（formatClockTime/formatDurationShort）

/** M5-1/R2：消息复制（clipboard API + 降级，统一走 lib/clipboard） */
async function copyMessage(content: string) {
  const ok = await copyText(content);
  if (ok) {
    toast.success('已复制');
  } else {
    toast.error('复制失败');
  }
}

/**
 * C1（参考稿对齐）：已完成轮次的"用时/执行过程"行——
 * 收起时为灰字「用时 X分X秒 ›」（与参考稿一致），展开回看各阶段。
 */
function ProcessSummary({ process }: { process: MessageProcess }) {
  const [open, setOpen] = useState(false);
  const elapsed = formatDurationShort(process.elapsedMs);
  if (!elapsed && process.stages.length === 0) return null;
  return (
    <div className="w-full">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="inline-flex items-center gap-1 rounded-md px-1 py-0.5 text-xs text-ink-faint transition-colors hover:text-ink-secondary focus-ring"
        aria-expanded={open}
      >
        {elapsed && <span>用时 {elapsed}</span>}
        {!elapsed && <span>执行过程 · {process.stages.length} 阶段</span>}
        <ChevronRight className={cn('h-3 w-3 transition-transform duration-fast', open && 'rotate-90')} />
      </button>
      {open && (
        <div className="mt-1 space-y-1 border-l-2 border-line pl-3">
          {process.stages.map((stage, idx) => (
            <p key={idx} className="text-xs text-ink-faint">
              <Check className="mr-1 inline h-3 w-3 text-success" />
              {stage}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}

export interface MessageBubbleProps {
  message: ChatMessage;
  /** 09 C-04：仅最后一条 assistant 消息提供"重新生成" */
  onRegenerate?: () => void;
  /** 09 C-04：user 消息"编辑并重发"（新 key 新轮次，历史不删改） */
  onEditResend?: (text: string) => void;
}

/**
 * M6-58/T10 + B3（09）：
 * - memo（PE-03/F-13：流式期间已完成气泡不随父级重渲）
 * - 气泡 + 时间戳 + 复制 + tokens/耗时（C-07）
 * - assistant 执行过程折叠摘要（C-03）
 * - 最后一条 assistant 消息"重新生成"；user 消息悬浮"编辑重发"（C-04）
 */
function MessageBubbleInner({ message, onRegenerate, onEditResend }: MessageBubbleProps) {
  const isUser = message.role === 'user';
  const [editing, setEditing] = useState(false);
  const [editText, setEditText] = useState(message.content);

  const startEdit = () => {
    setEditText(message.content);
    setEditing(true);
  };

  const cancelEdit = () => setEditing(false);

  const saveEdit = () => {
    const text = editText.trim();
    if (!text || text === message.content) {
      setEditing(false);
      return;
    }
    setEditing(false);
    onEditResend?.(text);
  };

  return (
    <div className={cn('group relative flex', isUser ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'flex flex-col',
          isUser ? 'items-end max-w-[85%]' : 'items-start w-full'
        )}
      >
      {/* C-03/C1r1(4)：执行过程摘要已并入回答体首行（用时行 → 分隔线 → 正文） */}
      <div
        className={cn(
          'relative px-4 py-2.5',
          // C1 参考稿对齐：用户消息=浅灰气泡右对齐；助手回答=无气泡通栏排版
          isUser
            ? 'bg-surface-2 text-ink rounded-2xl rounded-br-sm'
            : 'w-full'
        )}
      >
        {isUser ? (
            editing ? (
              <div className="w-72 space-y-2">
                <textarea
                  value={editText}
                  onChange={(e) => setEditText(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                      e.preventDefault();
                      saveEdit();
                    } else if (e.key === 'Escape') {
                      e.preventDefault();
                      cancelEdit();
                    }
                  }}
                  rows={3}
                  autoFocus
                  className="w-full resize-none rounded-lg border border-brand-300 bg-white/95 p-2 text-sm text-ink outline-none dark:bg-slate-900/95"
                  aria-label="编辑消息"
                />
                <div className="flex items-center justify-end gap-2 text-xs">
                  <span className="text-ink-faint">Enter+Ctrl 发送 · Esc 取消</span>
                  <button
                    type="button"
                    onClick={cancelEdit}
                    aria-label="取消编辑"
                    className="rounded p-1 text-ink-secondary hover:bg-black/10 focus-ring"
                  >
                    <X className="h-3.5 w-3.5" />
                  </button>
                  <button
                    type="button"
                    onClick={saveEdit}
                    aria-label="发送编辑后的消息"
                    className="rounded bg-brand-500 p-1 text-white hover:bg-brand-600 focus-ring"
                  >
                    <Check className="h-3.5 w-3.5" />
                  </button>
                </div>
              </div>
            ) : (
              <p className="whitespace-pre-wrap text-sm">{message.content}</p>
            )
          ) : (
            /* C1r1(4)：回答结构 = 「用时 xx ›」行 + 浅色横分隔线 + 正文（历史消息无 process 时仅正文） */
            <div className="w-full text-sm leading-relaxed">
              {message.process && <ProcessSummary process={message.process} />}
              {message.process && <div aria-hidden className="my-2 h-px w-full bg-line" />}
              <ChatMessageContent content={message.content} />
            </div>
          )}
        </div>
        {/* C-09：JSON 兜底路径携带 itineraryId 时的内联行程卡（SSE done 字段就绪后自动覆盖两条路径） */}
        {!isUser && message.itineraryId != null && (
          <Link
            href={buildItineraryUrl(message.itineraryId)}
            className="mt-1.5 inline-flex items-center gap-2 rounded-xl border border-line bg-surface px-3 py-2 text-sm text-ink shadow-1 transition-colors hover:border-brand-400 hover:text-brand-600 focus-ring animate-rise"
          >
            <Plane className="h-4 w-4 text-brand-500" />
            行程已生成 · 查看详情
          </Link>
        )}
        {/* M6-49/50 + C-07：时间戳（左）+ 元信息/操作（右）；复制按钮透明背景 */}
        <div
          className={cn(
            'mt-1 flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity',
            isUser ? 'justify-end' : 'justify-start'
          )}
        >
          <span className="text-[10px] text-ink-faint/70">
            {formatClockTime(message.createdAt)}
          </span>
          {!isUser && message.tokens != null && (
            <span className="text-[10px] text-ink-faint/70">· {message.tokens} tokens</span>
          )}
          {!isUser && onRegenerate && (
            <button
              type="button"
              onClick={onRegenerate}
              className="rounded-md bg-transparent p-1 text-ink-faint hover:text-brand-500 focus-ring"
              title="重新生成"
              aria-label="重新生成回复"
            >
              <RotateCcw className="h-3 w-3" />
            </button>
          )}
          {isUser && onEditResend && !editing && (
            <button
              type="button"
              onClick={startEdit}
              className="rounded-md bg-transparent p-1 text-ink-faint hover:text-brand-500 focus-ring"
              title="编辑并重发"
              aria-label="编辑并重发"
            >
              <Pencil className="h-3 w-3" />
            </button>
          )}
          <button
            type="button"
            onClick={() => copyMessage(message.content)}
            className="rounded-md bg-transparent p-1 text-ink-faint hover:text-brand-500 focus-ring"
            title="复制"
            aria-label="复制消息"
          >
            <Copy className="h-3 w-3" />
          </button>
        </div>
      </div>
    </div>
  );
}

export const MessageBubble = memo(MessageBubbleInner);

/** B3/09 C-03：进行中轮次的执行过程时间线（C1 去气泡：通栏灰字 + 最新行 spinner） */
export function ThinkingTimeline({ lines }: { lines: string[] }) {
  return (
    <div className="flex justify-start w-full">
      <div className="w-full">
        <div className="flex items-center gap-2 text-sm text-ink-faint">
          <Loader2 className="h-4 w-4 animate-spin" />
          Agent 执行中…
        </div>
        {lines.length > 0 && (
          <div
            role="log"
            aria-label="执行过程"
            className="mt-2 max-h-40 space-y-1 overflow-y-auto border-l-2 border-line pl-3"
          >
            {lines.map((line, idx) => (
              <p key={idx} className="flex items-start gap-1.5 text-xs text-ink-faint">
                {idx < lines.length - 1 ? (
                  <Check className="mt-0.5 h-3 w-3 flex-shrink-0 text-success" />
                ) : (
                  <Loader2 className="mt-0.5 h-3 w-3 flex-shrink-0 animate-spin text-brand-500" />
                )}
                <span>{line}</span>
              </p>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * B3/09 C-02：流式输出气泡。
 * Markdown 增量渲染（D-08 改判 β）+ 三守卫：
 * ① useThrottledValue 渲染节流 120ms；② react-markdown 对未闭合代码块天然容忍；
 * ③ localStorage `travel.chat.stream-markdown=false` 回退纯文本。
 * 文本尾部打字光标（reduced-motion 下被 globals.css 压为不显示）。
 */
export function StreamingBubble({ text }: { text: string }) {
  const throttled = useThrottledValue(text, 120);
  const [markdownEnabled] = useState(() => {
    if (typeof window === 'undefined') return true;
    try {
      return localStorage.getItem(STREAM_MARKDOWN_PREF_KEY) !== 'false';
    } catch {
      return true;
    }
  });

  return (
    <div className="flex justify-start w-full">
      <div className="w-full text-sm leading-relaxed">
        {markdownEnabled ? (
          <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-ul:my-1">
            <ChatMessageContent content={throttled} />
          </div>
        ) : (
          <p className="whitespace-pre-wrap">{throttled}</p>
        )}
        <span aria-hidden className="ml-0.5 inline-block h-4 w-0.5 bg-brand-500 animate-blink align-text-bottom" />
      </div>
    </div>
  );
}

/** M6-36：执行已中断 + 重试（C1 去气泡：通栏灰字行） */
export function InterruptedBubble({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex justify-start w-full">
      <div className="w-full">
        <p className="text-sm text-ink-secondary">执行已中断</p>
        <button
          type="button"
          onClick={onRetry}
          className="mt-1 inline-flex items-center gap-1 text-xs text-brand-500 hover:text-brand-600 focus-ring rounded"
        >
          <RotateCcw className="h-3 w-3" /> 重试
        </button>
      </div>
    </div>
  );
}
