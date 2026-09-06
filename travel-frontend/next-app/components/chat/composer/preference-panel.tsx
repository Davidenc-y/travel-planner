'use client';

import { useState } from 'react';
import { Tags, X } from 'lucide-react';
import { DestinationAutocomplete } from '@/components/plan/DestinationAutocomplete';
import { PagedMultiSelect, PagedSingleSelect } from '@/components/ui/paged-options';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import {
  INTEREST_OPTIONS,
  PARTY_OPTIONS,
  preferenceTagTexts,
  type PreferenceTags,
} from '@/lib/schemas';

/** M23c（E4）：偏好圆钮（锚定圆钮右侧同族）。 */
export function PreferenceDotButton({
  active,
  onClick,
}: {
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      aria-label="本轮偏好标签"
      title="本轮偏好标签"
      onClick={onClick}
      className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-full border transition-colors ${
        active ? 'border-brand bg-brand/10 text-brand' : 'border-line text-ink-faint hover:bg-surface-2'
      }`}
    >
      <Tags className="h-4 w-4" />
    </button>
  );
}

/** M23c（E4）：偏好标签行（发送区上侧，横向排布；每枚独立 × 移除对应字段）。 */
export function PreferenceTagsRow({
  tags,
  onRemoveField,
}: {
  tags: PreferenceTags;
  onRemoveField: (key: string) => void;
}) {
  const items = preferenceTagTexts(tags);
  if (items.length === 0) return null;
  return (
    <div className="flex flex-wrap items-center gap-1.5 pb-1.5">
      {items.map(({ key, text }) => (
        <span
          key={key}
          className="inline-flex max-w-56 items-center gap-1 rounded-full border border-brand/40 bg-brand/10 px-2 py-0.5 text-xs text-brand"
        >
          <span className="truncate">{text}</span>
          <button
            type="button"
            aria-label={`移除偏好 ${text}`}
            onClick={() => onRemoveField(key)}
            className="ml-0.5 hover:text-danger"
          >
            <X className="h-3 w-3" />
          </button>
        </span>
      ))}
    </div>
  );
}

/** M23c（E4）：偏好编辑面板（字段与原 /plan 表单同构；zod 即时校验）。 */
export function PreferencePanel({
  open,
  tags,
  onChange,
  onClose,
}: {
  open: boolean;
  tags: PreferenceTags;
  onChange: (tags: PreferenceTags) => void;
  onClose: () => void;
}) {
  const [error, setError] = useState<string | null>(null);
  if (!open) return null;

  const set = (patch: Partial<PreferenceTags>) => {
    setError(null);
    onChange({ ...tags, ...patch });
  };

  const finish = () => {
    // 轻校验：非法组合禁止关闭（zod 解析）
    if (tags.budget != null && tags.budget < 0) {
      setError('预算不能为负');
      return;
    }
    if (tags.days != null && (tags.days < 1 || tags.days > 30)) {
      setError('天数须在 1~30 之间');
      return;
    }
    onClose();
  };

  return (
    <div
      role="dialog"
      aria-label="本轮偏好标签"
      className="absolute bottom-full left-0 z-30 mb-2 w-96 max-w-[90vw] space-y-2.5 rounded-xl border border-line bg-surface p-3 shadow-lg"
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-medium">本轮偏好标签</span>
        <button type="button" aria-label="关闭" onClick={onClose} className="text-ink-faint hover:text-ink">
          <X className="h-4 w-4" />
        </button>
      </div>
      <div>
        <label className="mb-1 block text-xs text-ink-faint">目的地</label>
        <DestinationAutocomplete
          value={tags.destination ?? ''}
          onChange={(v) => set({ destination: v })}
          placeholder="如：成都"
        />
      </div>
      <div className="grid grid-cols-2 gap-2">
        <div>
          <label className="mb-1 block text-xs text-ink-faint">天数（1~30）</label>
          <Input
            type="number"
            min={1}
            max={30}
            value={tags.days ?? ''}
            onChange={(e) => set({ days: e.target.value === '' ? undefined : Number(e.target.value) })}
          />
        </div>
        <div>
          <label className="mb-1 block text-xs text-ink-faint">预算（元）</label>
          <Input
            type="number"
            min={0}
            value={tags.budget ?? ''}
            onChange={(e) => set({ budget: e.target.value === '' ? undefined : Number(e.target.value) })}
          />
        </div>
      </div>
      <div>
        <label className="mb-1 block text-xs text-ink-faint">同行人</label>
        <PagedSingleSelect
          options={PARTY_OPTIONS.map((p) => ({ label: p, value: p }))}
          value={tags.party}
          onChange={(v) => set({ party: v as PreferenceTags['party'] })}
          placeholder="选择同行人"
          dropUp
        />
      </div>
      <div>
        <label className="mb-1 block text-xs text-ink-faint">兴趣（最多 6 项）</label>
        <PagedMultiSelect
          options={INTEREST_OPTIONS.map((p) => ({ label: p, value: p }))}
          selected={tags.interests ?? []}
          onToggle={(v) => {
            const cur = tags.interests ?? [];
            const interest = v as (typeof INTEREST_OPTIONS)[number];
            set({
              interests: cur.includes(interest)
                ? cur.filter((x) => x !== interest)
                : [...cur, interest],
            } as PreferenceTags);
          }}
          placeholder="选择兴趣"
          dropUp
        />
      </div>
      <div>
        <label className="mb-1 block text-xs text-ink-faint">出发日期（选填）</label>
        <Input
          type="date"
          value={tags.startDate ?? ''}
          onChange={(e) => set({ startDate: e.target.value })}
        />
      </div>
      {error && <p className="text-xs text-danger">{error}</p>}
      <div className="flex items-center justify-between pt-1">
        <Button variant="ghost" size="sm" onClick={() => onChange({})}>
          清除全部
        </Button>
        <Button size="sm" onClick={finish}>
          完成
        </Button>
      </div>
      <p className="text-xs text-ink-faint">
        偏好作为本轮对话的结构化约束（优先级最高）；不会自动写入长期画像。
      </p>
    </div>
  );
}
