'use client';

/**
 * MI-7b：会话列表侧栏（自 chat-page-content 抽出，沿用一轮拆分模式）。
 *
 * <p>桌面侧栏（hidden md:flex）与窄屏抽屉（C-05 Dialog）双形态同源渲染——
 * 两者 props 完全一致，仅抽屉在 onSaveTitle 后额外收起。选择/编辑/关闭等
 * 编排与状态保留在容器（chat-page-content），props 全为可序列化的值/函数
 * 引用（规避 TS71007 误报）。</p>
 */
import { Dialog } from '@/components/ui/dialog';
import { SessionList } from '@/components/chat/SessionList';
import type { ComponentProps } from 'react';
import type { useSessionList } from '@/hooks/useSessionList';
import type { useChatStream } from '@/hooks/useChatStream';

type SessionListProps = ComponentProps<typeof SessionList>;

interface SessionListSidebarProps {
  /** 会话列表 hook 返回对象（列表/编辑/置顶状态，仅引用下传） */
  sessionList: ReturnType<typeof useSessionList>;
  /** 流式状态（轮次进行中的红点展示） */
  streamStates: ReturnType<typeof useChatStream>['streamStates'];
  currentSessionId: string | null;
  creatingSession: boolean;
  /** 已完成轮次（红点判定） */
  completedTurns: SessionListProps['completedTurns'];
  onNewSession: () => void;
  onSelect: (sid: string) => void;
  onEnterSelect: (sid: string) => void;
  onCloseSession: (sid: string) => void;
  /** 窄屏抽屉开闭状态（留容器） */
  drawerOpen: boolean;
  onCloseDrawer: () => void;
}

export function SessionListSidebar({
  sessionList,
  streamStates,
  currentSessionId,
  creatingSession,
  completedTurns,
  onNewSession,
  onSelect,
  onEnterSelect,
  onCloseSession,
  drawerOpen,
  onCloseDrawer,
}: SessionListSidebarProps) {
  return (
    <>
      {/* 会话列表（窄屏收进抽屉，C-05） */}
      <div className="hidden md:flex flex-shrink-0">
        <SessionList
          sessions={sessionList.sessions}
          currentSessionId={currentSessionId}
          creatingSession={creatingSession}
          streamStates={streamStates}
          completedTurns={completedTurns}
          editingSessionId={sessionList.editingSessionId}
          editingTitle={sessionList.editingTitle}
          pinnedIds={sessionList.pinnedIds}
          onTogglePin={sessionList.togglePin}
          onNewSession={onNewSession}
          onSelect={onSelect}
          onEnterSelect={onEnterSelect}
          onStartEdit={sessionList.startEdit}
          onTitleChange={sessionList.changeEditingTitle}
          onSaveTitle={sessionList.saveTitle}
          onCancelEdit={sessionList.cancelEdit}
          onCloseSession={onCloseSession}
        />
      </div>

      {/* 窄屏会话抽屉（C-05） */}
      <Dialog open={drawerOpen} onClose={onCloseDrawer} className="max-w-xs p-3" ariaLabel="会话列表">
        <SessionList
          sessions={sessionList.sessions}
          currentSessionId={currentSessionId}
          creatingSession={creatingSession}
          streamStates={streamStates}
          completedTurns={completedTurns}
          editingSessionId={sessionList.editingSessionId}
          editingTitle={sessionList.editingTitle}
          pinnedIds={sessionList.pinnedIds}
          onTogglePin={sessionList.togglePin}
          onNewSession={onNewSession}
          onSelect={onSelect}
          onEnterSelect={onEnterSelect}
          onStartEdit={sessionList.startEdit}
          onTitleChange={sessionList.changeEditingTitle}
          onSaveTitle={(sid) => {
            sessionList.saveTitle(sid);
            onCloseDrawer();
          }}
          onCancelEdit={sessionList.cancelEdit}
          onCloseSession={onCloseSession}
        />
      </Dialog>
    </>
  );
}
