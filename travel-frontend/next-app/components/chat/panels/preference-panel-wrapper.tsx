'use client';

/**
 * R3.1：偏好面板编排（自 chat-page-content 按面板 JSX 标签边界抽出）。
 *
 * <p>开闭状态与偏好标签状态保留在容器（chat-page-content），本组件只装配
 * 圆钮+编辑面板，并承接面板"完成"的关闭编排（party/interests 持久化到
 * 锚定行程约束列）。props 全为可序列化的值/函数引用（规避 TS71007 误报）。</p>
 */
import { toast } from 'sonner';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import type { PreferenceTags } from '@/lib/schemas';
import { PreferenceDotButton, PreferencePanel } from '@/components/chat/composer/preference-panel';

interface PreferencePanelWrapperProps {
  open: boolean;
  active: boolean;
  disabled?: boolean;
  tags: PreferenceTags;
  /** PATCH constraints 目标：容器按"已建会话取服务端首锚，草稿态取草稿首锚"解析后传入 */
  firstAnchorId?: number;
  onToggle: () => void;
  onChange: (tags: PreferenceTags) => void;
  /** 仅收起面板（开闭状态在容器） */
  onRequestClose: () => void;
  /** 持久化成功后同步锚定（是否/如何 load 由容器决定） */
  onAnchorsSynced: () => void;
}

export function PreferencePanelWrapper({
  open,
  active,
  disabled,
  tags,
  firstAnchorId,
  onToggle,
  onChange,
  onRequestClose,
  onAnchorsSynced,
}: PreferencePanelWrapperProps) {
  /**
   * M28-13：偏好面板"完成"——party/interests 持久化到（首个）锚定行程约束列
   * （用户显式确定性意图；days/budget/startDate 不直接写列，仍作为下一轮约束传
   * 给 AI，避免"天数变了但 dayPlans 未重排"的内容不一致）。
   */
  const handlePrefPanelClose = () => {
    onRequestClose();
    if (!firstAnchorId || (!tags.party && !tags.interests?.length)) return;
    itineraryApi.updateConstraints(firstAnchorId, {
      party: tags.party,
      interests: tags.interests?.length ? tags.interests : undefined,
    })
      .then(() => {
        toast.success('同行人与兴趣已同步到锚定行程');
        onAnchorsSynced();
      })
      .catch((err) => toast.error('偏好同步失败: ' + getErrorMessage(err)));
  };

  return (
    <>
      <PreferenceDotButton active={active} onClick={onToggle} disabled={disabled} />
      <PreferencePanel
        open={open}
        tags={tags}
        onChange={onChange}
        onClose={handlePrefPanelClose}
      />
    </>
  );
}
