'use client';

/**
 * R3.2：锚定面板编排（自 chat-page-content 按面板 JSX 标签边界抽出）。
 *
 * <p>开闭状态与锚定状态（服务端/草稿）保留在容器（chat-page-content），本组件装配
 * 锚定圆钮+行程选择面板，承接勾选编排（M28-13 填充绑定、M28-16 草稿单选、服务端
 * toggle）与 M28-15 锚定基准变化检测（SYSTEM 消息本地插入 + appendSystemNote 落库）。
 * props 全为可序列化的值/函数引用（规避 TS71007 误报）。</p>
 */
import { useEffect, useRef } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { chatApi } from '@/lib/api';
import type { AnchorBrief, ChatMessage } from '@/types';
import type { SessionAnchorState } from '@/hooks/useSessionAnchor';
import { AnchorDotButton, AnchorPanel } from '@/components/chat/composer/anchor-panel';

interface AnchorPanelWrapperProps {
  open: boolean;
  disabled?: boolean;
  /** 当前生效锚定 ids（已建会话=服务端态 / 新会话=草稿态，容器归一后传入） */
  effectiveAnchorIds: number[];
  /** 当前生效锚定 briefs（系统提示消息标题解析用） */
  effectiveAnchorBriefs: Record<number, AnchorBrief>;
  /** 服务端锚定状态（勾选判定 + 标题兜底解析） */
  anchorState: SessionAnchorState;
  /** 新会话草稿锚定 ids 与写入器（M28-16 单选语义落点；状态本体留容器） */
  draftAnchorIds: number[];
  setDraftAnchorIds: Dispatch<SetStateAction<number[]>>;
  currentSessionId: string | null;
  /** 容器 ref：变化检测 effect 内实时取当下会话（闭包值可能已过期） */
  currentSessionRef: { readonly current: string | null };
  /** 本地 SYSTEM 消息插入（容器消息状态写入器） */
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>;
  /** M28-13 勾选填充（函数体耦合容器状态留容器，仅引用下传——R3.1 裁定） */
  fillPreferenceFromItinerary: (id: number, checked: boolean) => void;
  /** 服务端锚定 toggle（useSessionAnchor 返回对象，仅消费 toggle） */
  anchor: { toggle: (sid: string, id: number) => Promise<void> };
  /** 仅开关面板（开闭状态在容器） */
  onToggle: () => void;
  /** 仅收起面板 */
  onRequestClose: () => void;
}

export function AnchorPanelWrapper({
  open,
  disabled,
  effectiveAnchorIds,
  effectiveAnchorBriefs,
  anchorState,
  draftAnchorIds,
  setDraftAnchorIds,
  currentSessionId,
  currentSessionRef,
  setMessages,
  fillPreferenceFromItinerary,
  anchor,
  onToggle,
  onRequestClose,
}: AnchorPanelWrapperProps) {
  // M28-15：锚定基准变化 → 会话内系统提示消息（落库 t_chat_message role=system，
  // 像一条消息永久显示在会话中央，半透明小字——替代 M28-14 的悬浮条）。
  // 单锚替换="已从行程X切换到行程Y"；增/删=锚定/取消；自动锚定同样落一条。
  const prevAnchorIdsRef = useRef<number[] | null>(null);
  useEffect(() => {
    const prev = prevAnchorIdsRef.current;
    prevAnchorIdsRef.current = effectiveAnchorIds;
    if (prev == null) return; // 首帧只记录基线
    const added = effectiveAnchorIds.filter((x) => !prev.includes(x));
    const removed = prev.filter((x) => !effectiveAnchorIds.includes(x));
    if (added.length === 0 && removed.length === 0) return;
    const sid = currentSessionRef.current;
    if (!sid) return; // 草稿态暂不落库（首条消息后自然有自动锚定提示）
    const titleOf = (id: number) =>
      (effectiveAnchorBriefs as Record<number, { id: number; title?: string }>)?.[id]?.title
      ?? (anchorState.briefs as Record<number, { title?: string }>)?.[id]?.title
      ?? `行程 #${id}`;
    let text: string;
    if (added.length === 1 && removed.length === 1) {
      text = `已从行程「${titleOf(removed[0])}」切换到行程「${titleOf(added[0])}」`;
    } else if (added.length === 1) {
      text = `已锚定行程「${titleOf(added[0])}」`;
    } else if (removed.length === 1) {
      text = `已取消锚定行程「${titleOf(removed[0])}」`;
    } else {
      text = `锚定基准已更新（新增 ${added.length}、取消 ${removed.length}）`;
    }
    // 本地即时插入 + 后端落库（历史接口返回后刷新仍在）
    setMessages((prev) => [...prev, {
      sessionId: sid,
      role: 'system',
      content: text,
      createdAt: new Date().toISOString(),
      localKey: `sys-${crypto.randomUUID()}`,
    } as ChatMessage]);
    chatApi.appendSystemNote(sid, text).catch(() => { /* 落库失败不阻断（本地仍可见） */ });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveAnchorIds]);

  return (
    <>
      <AnchorDotButton
        count={effectiveAnchorIds.length}
        active={effectiveAnchorIds.length > 0}
        onClick={onToggle}
        disabled={disabled}
      />
      <AnchorPanel
        open={open}
        onClose={onRequestClose}
        anchoredIds={effectiveAnchorIds}
        onToggle={(id) => {
          // M28-13/M28-14：勾选时拉详情填充偏好（草稿与已建会话统一——
          // "锚定=基准"心智；已建会话此前漏调填充）；已建会话服务端 toggle
          // （per-turn truth 不变），新会话草稿本地勾选
          const checked = currentSessionId
            ? !anchorState.ids.includes(id)
            : !draftAnchorIds.includes(id);
          fillPreferenceFromItinerary(id, checked);
          if (currentSessionId) {
            void anchor.toggle(currentSessionId, id);
          } else {
            // M28-16：草稿锚定同样单选（勾新=替换）
            setDraftAnchorIds(checked ? [id] : []);
          }
        }}
        disabled={disabled}
      />
    </>
  );
}
