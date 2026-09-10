'use client';

/**
 * R3.3：消息流区域（自 chat-page-content 按滚动容器内消息内容块 JSX 边界抽出）。
 *
 * <p>承接空态推荐词（B3/09 C-06）、消息列表 messages.map 装配（MessageBubble +
 * C-12 日期分隔）与 M6 三行中央提示（ThinkingTimeline/StreamingBubble/InterruptedBubble）。
 * 滚动容器 scrollRef/onScroll、TurnScrollbar、回底按钮与 messagesEndRef 等 ref 耦合
 * 逻辑留容器；发送编排（applySuggestion/handleRegenerate/handleEditResend/handleRetry）
 * 函数体耦合容器状态留容器、仅引用下传（同 R3.1 fillPreferenceFromItinerary 先例）。
 * props 全为可序列化的值/函数引用（规避 TS71007 误报）。展示件 MessageBubble 零改动。</p>
 */
import { MessagesSquare } from 'lucide-react';
import { SUGGESTED_PROMPTS } from '@/lib/suggested-prompts';
import type { ChatMessage } from '@/types';
import type { StreamState } from '@/hooks/useChatStream';
import {
  InterruptedBubble,
  MessageBubble,
  StreamingBubble,
  ThinkingTimeline,
} from '@/components/chat/MessageBubble';

export interface InterruptedTurn {
  clientMessageId: string;
  text: string;
}

// B3/09 C-12：日期分隔（本地日期粒度）
function sameDay(a?: string, b?: string): boolean {
  if (!a || !b) return false;
  const da = new Date(a);
  const db = new Date(b);
  if (Number.isNaN(da.getTime()) || Number.isNaN(db.getTime())) return false;
  return da.getFullYear() === db.getFullYear()
    && da.getMonth() === db.getMonth()
    && da.getDate() === db.getDate();
}

function DateSeparator({ iso }: { iso: string }) {
  const d = new Date(iso);
  const now = new Date();
  const sameDayNow = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  const label = sameDayNow
    ? '今天'
    : `${d.getMonth() + 1} 月 ${d.getDate()} 日`;
  return (
    <div className="flex items-center gap-3 py-1" aria-hidden>
      <span className="h-px flex-1 bg-line" />
      <span className="text-[10px] text-ink-faint">{label}</span>
      <span className="h-px flex-1 bg-line" />
    </div>
  );
}

/** B3：消息列表是否以 user 消息结尾（用于判断最后一条 assistant） */
function isLastMessageUser(list: ChatMessage[]): boolean {
  return list.length > 0 && list[list.length - 1].role === 'user';
}

interface MessageStreamWrapperProps {
  /** 消息列表（状态本体留容器） */
  messages: ChatMessage[];
  currentSessionId: string | null;
  /** 当前会话流式状态快照（容器派生后传入） */
  currentStreamState?: StreamState;
  /** M6-36 每会话中断轮次映射（状态本体留容器） */
  interruptedTurns: Record<string, InterruptedTurn>;
  /** B3/09 C-06 推荐词填充草稿（容器草稿编排，仅引用下传） */
  applySuggestion: (prompt: string) => void;
  /** B3/09 C-04 重新生成（容器发送编排，仅引用下传） */
  handleRegenerate: (sid: string) => void;
  /** B3/09 C-04 编辑重发（容器发送编排，仅引用下传） */
  handleEditResend: (text: string) => void;
  /** M6-36 断点重试（容器发送编排，仅引用下传） */
  handleRetry: (sid: string) => void;
}

export function MessageStreamWrapper({
  messages,
  currentSessionId,
  currentStreamState,
  interruptedTurns,
  applySuggestion,
  handleRegenerate,
  handleEditResend,
  handleRetry,
}: MessageStreamWrapperProps) {
  return (
    <>
      {messages.length === 0 && !currentStreamState ? (
        /* B3/09 C-06：空态——能力说明 + 推荐提示词（点击填充草稿，不自动发送） */
        <div className="flex flex-col items-center justify-center h-full px-4 text-ink-faint">
          <MessagesSquare className="h-12 w-12 mb-3 opacity-50" />
          <p className="text-sm">开始一段新的旅游规划对话</p>
          <p className="mt-1 text-xs">我可以规划行程、检索景点，并记住你的旅行偏好</p>
          <div className="mt-6 grid w-full max-w-md grid-cols-1 sm:grid-cols-2 gap-2">
            {SUGGESTED_PROMPTS.map((prompt) => (
              <button
                key={prompt}
                type="button"
                onClick={() => applySuggestion(prompt)}
                className="rounded-xl border border-line bg-surface px-3 py-2 text-left text-xs text-ink-secondary transition-colors hover:border-brand-400 hover:text-ink focus-ring"
              >
                {prompt}
              </button>
            ))}
          </div>
        </div>
      ) : (
        messages.map((msg, idx) => {
          const showSeparator = idx === 0 || !sameDay(messages[idx - 1].createdAt, msg.createdAt);
          const isLastAssistant = !isLastMessageUser(messages)
            && idx === messages.length - 1 && msg.role === 'assistant';
          const key = msg.id ?? msg.localKey ?? `i-${idx}`;
          return (
            <div
              key={key}
              data-user-turn={msg.role === 'user' ? key : undefined}
              className="space-y-4"
            >
              {showSeparator && msg.createdAt && <DateSeparator iso={msg.createdAt} />}
              <MessageBubble
                message={msg}
                onRegenerate={isLastAssistant ? () => handleRegenerate(currentSessionId!) : undefined}
                onEditResend={msg.role === 'user' ? handleEditResend : undefined}
              />
            </div>
          );
        })
      )}
      {/* M6：执行过程时间线（C-03，替代原 ThinkingBubble） */}
      {currentStreamState?.phase === 'thinking' && (
        <ThinkingTimeline lines={currentStreamState.thinkingLines} />
      )}
      {/* M6：流式输出（思考完成后替换时间线；C-02 Markdown 增量渲染） */}
      {currentStreamState?.phase === 'streaming' && (
        <StreamingBubble text={currentStreamState.streamingText} />
      )}
      {/* M6-36：执行已中断 + 重试（每会话最多一个断点） */}
      {interruptedTurns[currentSessionId ?? ''] && (
        <InterruptedBubble onRetry={() => handleRetry(currentSessionId!)} />
      )}
    </>
  );
}
