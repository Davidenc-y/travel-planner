'use client';

import { Copy, Loader2, RotateCcw } from 'lucide-react';
import { toast } from 'sonner';
import type { ChatMessage } from '@/types';
import { cn } from '@/lib/utils';
import { ChatMessageContent } from '@/components/feature/chat-message-content';

// M6-36：消息时间戳（同日 HH:mm，跨日 MM-DD HH:mm；本地兜底当前时间）
function formatMessageTime(iso?: string): string {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`;
}

// M5-1：消息复制（clipboard API + 降级）
async function copyMessage(content: string) {
  try {
    await navigator.clipboard.writeText(content);
    toast.success('已复制');
  } catch {
    try {
      const ta = document.createElement('textarea');
      ta.value = content;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      toast.success('已复制');
    } catch {
      toast.error('复制失败');
    }
  }
}

/**
 * M6-58/T10：已完成消息气泡（纯展示）。
 * 气泡 + 时间戳 + 复制按钮；样式与迁移前逐行等价（M6-49/M6-50）。
 */
export function MessageBubble({ message }: { message: ChatMessage }) {
  return (
    <div className={cn('group relative flex', message.role === 'user' ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'flex max-w-[70%] flex-col',
          message.role === 'user' ? 'items-end' : 'items-start'
        )}
      >
        <div
          className={cn(
            'relative rounded-2xl px-4 py-2.5',
            message.role === 'user'
              ? 'bg-brand-500 text-white rounded-br-sm'
              : 'bg-slate-100 dark:bg-slate-800 rounded-bl-sm'
          )}
        >
          {message.role === 'user' ? (
            <p className="text-sm whitespace-pre-wrap">{message.content}</p>
          ) : (
            <div className="text-sm">
              <ChatMessageContent content={message.content} />
            </div>
          )}
        </div>
        {/* M6-49：时间戳与复制按钮在气泡外（左下角），复制按钮透明背景 */}
        <div
          className={cn(
            'mt-1 flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity',
            message.role === 'user' ? 'justify-end' : 'justify-start'
          )}
        >
          {/* M6-50：时间戳在左、复制按钮在右 */}
          <span className="text-[10px] text-slate-400/70">
            {formatMessageTime(message.createdAt)}
          </span>
          <button
            type="button"
            onClick={() => copyMessage(message.content)}
            className="rounded-md bg-transparent p-1 text-slate-400 hover:text-brand-500"
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

/** M6：思考气泡（spinner + 浅灰半透明阶段提示） */
export function ThinkingBubble({ lines }: { lines: string[] }) {
  return (
    <div className="flex justify-start">
      <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100/70 dark:bg-slate-800/70">
        <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
          <Loader2 className="h-4 w-4 animate-spin" />
          Agent 思考中…
        </div>
        {lines.length > 0 && (
          <div className="mt-2 space-y-1">
            {lines.map((line, idx) => (
              <p key={idx} className="text-xs text-slate-500/80 dark:text-slate-400/80">
                {line}
              </p>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** M6：流式输出气泡（逐字揭示文本容器，展示层纯渲染） */
export function StreamingBubble({ text }: { text: string }) {
  return (
    <div className="flex justify-start">
      <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100 dark:bg-slate-800">
        <p className="text-sm whitespace-pre-wrap">{text}</p>
      </div>
    </div>
  );
}

/** M6-36：执行已中断 + 重试（每会话最多一个断点） */
export function InterruptedBubble({ onRetry }: { onRetry: () => void }) {
  return (
    <div className="flex justify-start">
      <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100 dark:bg-slate-800">
        <p className="text-sm text-slate-600 dark:text-slate-300">执行已中断</p>
        <button
          type="button"
          onClick={onRetry}
          className="mt-1.5 inline-flex items-center gap-1 text-xs text-brand-500 hover:text-brand-600"
        >
          <RotateCcw className="h-3 w-3" /> 重试
        </button>
      </div>
    </div>
  );
}
