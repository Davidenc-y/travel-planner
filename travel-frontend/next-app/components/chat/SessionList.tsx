'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { Archive, Loader2, MessageSquare, Pin, PinOff, Plus, Search } from 'lucide-react';
import type { ChatSession } from '@/types';
import { cn } from '@/lib/utils';
import { formatRelativeTime } from '@/lib/time-format';
import type { StreamState } from '@/hooks/useChatStream';

interface SessionListProps {
  sessions: ChatSession[];
  currentSessionId: string | null;
  creatingSession: boolean;
  streamStates: Record<string, StreamState>;
  completedTurns: Record<string, boolean>;
  editingSessionId: string | null;
  editingTitle: string;
  /** C2：置顶会话 id 集合（localStorage 持久化，来自 useSessionList） */
  pinnedIds: string[];
  /** C2：置顶/取消置顶 */
  onTogglePin: (sid: string) => void;
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

// C2：会话时间分组（纯展示，不改后端排序契约）
type GroupKey = 'today' | 'week' | 'earlier';

const GROUP_LABELS: Record<GroupKey, string> = {
  today: '今天',
  week: '最近 7 天',
  earlier: '更早',
};

function groupOf(iso?: string): GroupKey {
  if (!iso) return 'earlier';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return 'earlier';
  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (d.getTime() >= startOfToday) return 'today';
  if (d.getTime() >= startOfToday - 6 * 24 * 3600 * 1000) return 'week';
  return 'earlier';
}

/** C2(3)/R2：相对时间实现已统一迁至 lib/time-format（formatRelativeTime） */

/** C2(3)：now 的轮询更新（30s 一拍，分钟级展示足够） */
function useNow(intervalMs = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

/**
 * M6-58/T10 + B3(09 C-05) + C2：会话列表面板。
 * 新增：置顶/取消置顶按钮（位于结束会话按钮左侧，前端本地持久化）、
 * 相对时间标签（位于置顶按钮左侧，30s 轮询刷新，最大时间单位/最小分钟/刚刚）、
 * 会话栏加宽 w-72、标题超长 truncate 省略。
 * 保留：搜索过滤、时间分组、会话项/Loader/红点/双击标题编辑语义逐行等价
 * （M4-9/M6-48/M6-49/M5-1）；Cmd/Ctrl+K 聚焦搜索。
 */
export function SessionList({
  sessions,
  currentSessionId,
  creatingSession,
  streamStates,
  completedTurns,
  editingSessionId,
  editingTitle,
  pinnedIds,
  onTogglePin,
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
  const searchInputRef = useRef<HTMLInputElement>(null);
  const [query, setQuery] = useState('');
  const now = useNow();
  const pinnedSet = useMemo(() => new Set(pinnedIds), [pinnedIds]);

  // M5-1：标题编辑态聚焦
  useEffect(() => {
    if (editingSessionId) {
      titleInputRef.current?.focus();
      titleInputRef.current?.select();
    }
  }, [editingSessionId]);

  // C-11：Cmd/Ctrl+K 聚焦搜索
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        searchInputRef.current?.focus();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return sessions;
    return sessions.filter((s) => s.title.toLowerCase().includes(q));
  }, [sessions, query]);

  const groups = useMemo(() => {
    const map = new Map<GroupKey, ChatSession[]>();
    for (const s of filtered) {
      const key = groupOf(s.createdAt);
      const list = map.get(key) ?? [];
      list.push(s);
      map.set(key, list);
    }
    return map;
  }, [filtered]);

  const renderSession = (s: ChatSession) => {
    const isPinned = pinnedSet.has(s.sessionId);
    const isEditing = editingSessionId === s.sessionId;
    return (
      <div key={s.sessionId} className="group relative">
        <div
          role="button"
          tabIndex={0}
          onClick={() => {
            if (!isEditing) {
              onSelect(s.sessionId);
            }
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !isEditing) {
              onEnterSelect(s.sessionId);
            }
          }}
          className={cn(
            'w-full flex items-center gap-1.5 px-2.5 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors focus-ring',
            currentSessionId === s.sessionId
              ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
              : 'hover:bg-surface-2'
          )}
        >
          {/* M6-48：思考/回复中 → 动态加载图标 */}
          {streamStates[s.sessionId]?.phase === 'thinking'
            || streamStates[s.sessionId]?.phase === 'streaming' ? (
            <Loader2 className="h-4 w-4 flex-shrink-0 animate-spin text-brand-500" />
          ) : (
            <MessageSquare className="h-4 w-4 flex-shrink-0" />
          )}
          {isEditing ? (
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
            <span className="flex items-center gap-1 flex-1 min-w-0">
              {/* C2：置顶会话常显图钉标识 */}
              {isPinned && (
                <Pin
                  className="h-3 w-3 flex-shrink-0 text-brand-500"
                  fill="currentColor"
                  aria-label="已置顶"
                />
              )}
              <span className="truncate" title={s.title}>
                {s.title}
              </span>
            </span>
          )}
          {/* M6-48：后台会话完成回复 → 红点提示（点进会话后清除） */}
          {completedTurns[s.sessionId] && currentSessionId !== s.sessionId && (
            <span
              title="有新回复"
              className="flex-shrink-0 h-2 w-2 rounded-full bg-danger animate-pulse-soft"
            />
          )}
          {/* C2(3)：相对时间（置顶按钮左侧，30s 轮询刷新） */}
          {!isEditing && (
            <span className="flex-shrink-0 text-[10px] text-ink-faint/70">
              {formatRelativeTime(s.createdAt, now)}
            </span>
          )}
          {/* C2(2)：置顶/取消置顶（位于结束会话按钮左侧；编辑态隐藏避免误触） */}
          {!isEditing && (
            <button
              title={isPinned ? '取消置顶' : '置顶'}
              aria-label={isPinned ? '取消置顶' : '置顶'}
              onClick={(e) => {
                e.stopPropagation();
                onTogglePin(s.sessionId);
              }}
              className={cn(
                'flex-shrink-0 p-1 rounded-md transition-all focus-ring',
                'opacity-0 group-hover:opacity-100',
                isPinned
                  ? 'text-brand-500 hover:text-brand-600'
                  : 'text-ink-faint hover:text-brand-500'
              )}
            >
              {isPinned ? <PinOff className="h-3.5 w-3.5" /> : <Pin className="h-3.5 w-3.5" />}
            </button>
          )}
          {/* M4-9：显式结束会话（编辑态隐藏避免误触） */}
          {!isEditing && (
            <button
              title="结束会话"
              aria-label="结束会话"
              onClick={(e) => {
                e.stopPropagation();
                onCloseSession(s.sessionId);
              }}
              className="flex-shrink-0 p-1 rounded-md opacity-0 group-hover:opacity-100 hover:bg-danger-soft text-ink-faint hover:text-danger transition-all focus-ring"
            >
              <Archive className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </div>
    );
  };

  return (
    <div className="w-72 flex-shrink-0 card flex flex-col p-3">
      <button
        onClick={onNewSession}
        disabled={creatingSession}
        className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 mb-2 magnetic disabled:opacity-50 focus-ring"
      >
        <Plus className="h-4 w-4" /> 新会话
      </button>
      {/* C-05：本地搜索（Cmd/Ctrl+K 聚焦） */}
      <div className="relative mb-2">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-ink-faint" />
        <input
          ref={searchInputRef}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Escape' && setQuery('')}
          placeholder="搜索会话…"
          aria-label="搜索会话"
          className="w-full rounded-lg border border-line bg-transparent py-1.5 pl-8 pr-2 text-xs outline-none placeholder:text-ink-faint focus:border-brand-500 focus:ring-2 focus:ring-brand-500"
        />
      </div>
      <div className="flex-1 space-y-1 overflow-y-auto">
        {sessions.length === 0 && (
          <p className="text-xs text-ink-faint text-center py-4">暂无会话</p>
        )}
        {sessions.length > 0 && filtered.length === 0 && (
          <p className="text-xs text-ink-faint text-center py-4">无匹配会话</p>
        )}
        {(['today', 'week', 'earlier'] as GroupKey[]).map((key) => {
          const list = groups.get(key);
          if (!list || list.length === 0) return null;
          return (
            <div key={key}>
              {query.trim() === '' && (
                <p className="px-3 pb-1 pt-2 text-[10px] font-medium uppercase tracking-wide text-ink-faint">
                  {GROUP_LABELS[key]}
                </p>
              )}
              <div className="space-y-1">{list.map(renderSession)}</div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
