'use client';

import { useEffect, useRef } from 'react';
import { Archive, Loader2, MessageSquare, Plus } from 'lucide-react';
import type { ChatSession } from '@/types';
import { cn } from '@/lib/utils';
import type { StreamState } from '@/hooks/useChatStream';

interface SessionListProps {
  sessions: ChatSession[];
  currentSessionId: string | null;
  creatingSession: boolean;
  streamStates: Record<string, StreamState>;
  completedTurns: Record<string, boolean>;
  editingSessionId: string | null;
  editingTitle: string;
  onNewSession: () => void;
  onSelect: (sid: string) => void;
  /** 键盘 Enter 选中（迁移前仅设置当前会话、不清红点，保持逐行为等价） */
  onEnterSelect: (sid: string) => void;
  onStartEdit: (session: ChatSession) => void;
  onTitleChange: (value: string) => void;
  onSaveTitle: (sid: string) => void;
  onCancelEdit: () => void;
  onCloseSession: (sid: string) => void;
}

/**
 * M6-58/T10：会话列表面板（纯展示 + 局部编辑态）。
 * 会话项 + Loader2 动态图标 + 红点（后台完成回复）+ 结束按钮 + 双击标题编辑；
 * 样式与交互语义与迁移前逐行等价（M4-9/M6-48/M6-49/M5-1）。
 */
export function SessionList({
  sessions,
  currentSessionId,
  creatingSession,
  streamStates,
  completedTurns,
  editingSessionId,
  editingTitle,
  onNewSession,
  onSelect,
  onEnterSelect,
  onStartEdit,
  onTitleChange,
  onSaveTitle,
  onCancelEdit,
  onCloseSession,
}: SessionListProps) {
  const titleInputRef = useRef<HTMLInputElement>(null);

  // M5-1：标题编辑态聚焦
  useEffect(() => {
    if (editingSessionId) {
      titleInputRef.current?.focus();
      titleInputRef.current?.select();
    }
  }, [editingSessionId]);

  return (
    <div className="w-64 flex-shrink-0 glass rounded-xl p-3 overflow-y-auto">
      <button
        onClick={onNewSession}
        disabled={creatingSession}
        className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 mb-3 magnetic disabled:opacity-50"
      >
        <Plus className="h-4 w-4" /> 新会话
      </button>
      <div className="space-y-1">
        {sessions.map((s) => (
          <div key={s.sessionId} className="group relative">
            <div
              role="button"
              tabIndex={0}
              onClick={() => {
                if (editingSessionId !== s.sessionId) {
                  onSelect(s.sessionId);
                }
              }}
              onKeyDown={(e) => {
                if (e.key === 'Enter' && editingSessionId !== s.sessionId) {
                  onEnterSelect(s.sessionId);
                }
              }}
              className={cn(
                'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors pr-8',
                currentSessionId === s.sessionId
                  ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
                  : 'hover:bg-slate-100 dark:hover:bg-slate-800'
              )}
            >
              {/* M6-48：思考/回复中 → 动态加载图标 */}
              {streamStates[s.sessionId]?.phase === 'thinking'
                || streamStates[s.sessionId]?.phase === 'streaming' ? (
                <Loader2 className="h-4 w-4 flex-shrink-0 animate-spin text-brand-500" />
              ) : (
                <MessageSquare className="h-4 w-4 flex-shrink-0" />
              )}
              {editingSessionId === s.sessionId ? (
                <input
                  ref={titleInputRef}
                  value={editingTitle}
                  onChange={(e) => onTitleChange(e.target.value)}
                  onClick={(e) => e.stopPropagation()}
                  onDoubleClick={(e) => e.stopPropagation()}
                  onBlur={() => onSaveTitle(s.sessionId)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      onSaveTitle(s.sessionId);
                    } else if (e.key === 'Escape') {
                      onCancelEdit();
                    }
                  }}
                  className="flex-1 min-w-0 rounded px-1 text-sm bg-white/70 dark:bg-slate-900/70 ring-1 ring-brand-500 outline-none"
                />
              ) : (
                <span
                  className="truncate flex-1"
                  title={s.title}
                  onDoubleClick={(e) => {
                    e.stopPropagation();
                    onStartEdit(s);
                  }}
                >
                  {s.title}
                </span>
              )}
            </div>
            {/* M4-9：显式结束会话（编辑态隐藏避免误触） */}
            {editingSessionId !== s.sessionId && (
              <button
                title="结束会话"
                onClick={() => onCloseSession(s.sessionId)}
                className="absolute right-1.5 top-1/2 -translate-y-1/2 p-1 rounded-md opacity-0 group-hover:opacity-100 hover:bg-red-50 dark:hover:bg-red-900/20 text-slate-400 hover:text-red-500 transition-all"
              >
                <Archive className="h-3.5 w-3.5" />
              </button>
            )}
            {/* M6-48：后台会话完成回复 → 红点提示（点进会话后清除） */}
            {completedTurns[s.sessionId] && currentSessionId !== s.sessionId && (
              <span
                title="有新回复"
                className="absolute right-7 top-1/2 -translate-y-1/2 h-2 w-2 rounded-full bg-red-500"
              />
            )}
          </div>
        ))}
        {sessions.length === 0 && (
          <p className="text-xs text-slate-400 text-center py-4">暂无会话</p>
        )}
      </div>
    </div>
  );
}
