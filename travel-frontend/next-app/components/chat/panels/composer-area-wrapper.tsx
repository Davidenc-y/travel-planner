'use client';

/**
 * MI-7：输入区装配（自 chat-page-content 按 Composer JSX 标签边界抽出，沿用一轮拆分模式）。
 *
 * <p>输入/发送/面板开闭等状态保留在容器（chat-page-content），本组件装配 Composer
 * 与其四槽（模型选择条/锚定面板/偏好面板/锚定标签行/偏好标签行）——面板编排沿
 * AnchorPanelWrapper/PreferencePanelWrapper 既有拆分。props 全为可序列化的值/函数
 * 引用（规避 TS71007 误报）。</p>
 */
import type { Dispatch, RefObject, SetStateAction } from 'react';
import type { ComponentProps } from 'react';
import type { AnchorBrief, ChatMessage } from '@/types';
import type { PreferenceTags } from '@/lib/schemas';
import type { SessionAnchorState } from '@/hooks/useSessionAnchor';
import { Composer } from '@/components/chat/Composer';
import { AnchorTags } from '@/components/chat/composer/anchor-panel';
import { AnchorPanelWrapper } from '@/components/chat/panels/anchor-panel-wrapper';
import { PreferenceTagsRow } from '@/components/chat/composer/preference-panel';
import { PreferencePanelWrapper } from '@/components/chat/panels/preference-panel-wrapper';
import { ModelSelector } from '@/components/model/ModelSelector';

interface ComposerAreaWrapperProps {
  input: string;
  /** 草稿写入器（按会话隔离的草稿状态留容器） */
  onInputChange: (v: string) => void;
  onSend: () => void;
  onStop: () => void;
  showStop: boolean;
  canSend: boolean;
  /** 流式进行中（面板禁用与停止态判定用） */
  sending: boolean;
  currentSessionId: string | null;
  /** 模型偏好（useModelPreference 返回对象，仅消费 model/select） */
  modelPref: { model: string; select: (m: string) => void };
  anchorPanelOpen: boolean;
  onToggleAnchorPanel: () => void;
  onRequestCloseAnchorPanel: () => void;
  /** 当前生效锚定 ids（已建会话=服务端态 / 新会话=草稿态，容器归一后传入） */
  effectiveAnchorIds: number[];
  /** 当前生效锚定 briefs（标签行展示） */
  effectiveAnchorBriefs: Record<number, AnchorBrief>;
  /** 服务端锚定状态（标签行移除判定） */
  anchorState: SessionAnchorState;
  /** 新会话草稿锚定 ids 与写入器（状态本体留容器） */
  draftAnchorIds: number[];
  setDraftAnchorIds: Dispatch<SetStateAction<number[]>>;
  /** 容器 ref：锚定移除走服务端分支时判定会话态 */
  currentSessionRef: { readonly current: string | null };
  /** 本地消息写入器（锚定面板 SYSTEM 消息插入） */
  setMessages: Dispatch<SetStateAction<ChatMessage[]>>;
  /** M28-13 勾选填充（函数体耦合容器状态留容器，仅引用下传） */
  fillPreferenceFromItinerary: (id: number, checked: boolean) => void;
  /** 服务端锚定操作（useSessionAnchor 返回对象，仅消费 toggle/load/clear） */
  anchor: {
    toggle: (sid: string, id: number) => Promise<void>;
    load: (sid: string) => Promise<void>;
    clear: (sid: string) => Promise<void>;
  };
  prefPanelOpen: boolean;
  onTogglePrefPanel: () => void;
  onRequestClosePrefPanel: () => void;
  /** 偏好标签（prefDraftKey 解析后的当前草稿/会话标签） */
  prefTags: PreferenceTags;
  /** 草稿键（标签移除的落点判定在容器 onRemovePreferenceTag 闭包内） */
  prefDraftKey: string;
  /** 偏好面板"变更"写入器（闭包耦合容器偏好状态留容器，仅引用下传） */
  onPreferenceTagsChange: (tags: PreferenceTags) => void;
  /** 标签移除编排（闭包耦合容器偏好状态留容器，仅引用下传） */
  onRemovePreferenceTag: (key: string) => void;
  /** Composer 受控 textarea ref（类型镜像 Composer 自身 prop 声明） */
  textareaRef: ComponentProps<typeof Composer>['textareaRef'];
}

export function ComposerAreaWrapper({
  input,
  onInputChange,
  onSend,
  onStop,
  showStop,
  canSend,
  sending,
  currentSessionId,
  modelPref,
  anchorPanelOpen,
  onToggleAnchorPanel,
  onRequestCloseAnchorPanel,
  effectiveAnchorIds,
  effectiveAnchorBriefs,
  anchorState,
  draftAnchorIds,
  setDraftAnchorIds,
  currentSessionRef,
  setMessages,
  fillPreferenceFromItinerary,
  anchor,
  prefPanelOpen,
  onTogglePrefPanel,
  onRequestClosePrefPanel,
  prefTags,
  prefDraftKey,
  onPreferenceTagsChange,
  onRemovePreferenceTag,
  textareaRef,
}: ComposerAreaWrapperProps) {
  return (
    <Composer
      value={input}
      onChange={(v) => onInputChange(v)}
      onSend={onSend}
      onStop={onStop}
      showStop={showStop}
      canSend={canSend}
      modelSlot={
        <ModelSelector value={modelPref.model} onChange={modelPref.select} dropUp compact />
      }
      anchorSlot={
        <div className="relative flex items-center gap-1">
          <AnchorPanelWrapper
            open={anchorPanelOpen}
            disabled={sending}
            effectiveAnchorIds={effectiveAnchorIds}
            effectiveAnchorBriefs={effectiveAnchorBriefs}
            anchorState={anchorState}
            draftAnchorIds={draftAnchorIds}
            setDraftAnchorIds={setDraftAnchorIds}
            currentSessionId={currentSessionId}
            currentSessionRef={currentSessionRef}
            setMessages={setMessages}
            fillPreferenceFromItinerary={fillPreferenceFromItinerary}
            anchor={anchor}
            onToggle={onToggleAnchorPanel}
            onRequestClose={onRequestCloseAnchorPanel}
          />
          <PreferencePanelWrapper
            open={prefPanelOpen}
            active={prefTags && Object.keys(prefTags).length > 0}
            disabled={sending}
            tags={prefTags}
            firstAnchorId={currentSessionId ? anchorState.ids[0] : draftAnchorIds[0]}
            onToggle={onTogglePrefPanel}
            onChange={onPreferenceTagsChange}
            onRequestClose={onRequestClosePrefPanel}
            onAnchorsSynced={() => {
              if (currentSessionId) void anchor.load(currentSessionId);
            }}
          />
        </div>
      }
      anchorTags={
        <AnchorTags
          briefs={effectiveAnchorBriefs}
          ids={effectiveAnchorIds}
          onRemove={(id) => {
            if (currentSessionId) {
              void anchor.toggle(currentSessionId, id);
            } else {
              setDraftAnchorIds((prev) => prev.filter((x) => x !== id));
            }
          }}
          disabled={sending}
        />
      }
      preferenceTags={
        <PreferenceTagsRow
          tags={prefTags}
          onRemoveField={onRemovePreferenceTag}
          disabled={sending}
        />
      }
      textareaRef={textareaRef}
    />
  );
}
