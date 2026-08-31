'use client';

import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { PagedSingleSelect } from '@/components/ui/paged-options';
import { getErrorMessage, modelApi } from '@/lib/api';

const SMART_OPTION = { value: '', label: '智能默认' };

interface ModelSelectorProps {
  value: string;
  onChange: (value: string) => void;
  /** M7-7：面板向上展开（聊天输入区贴底时使用） */
  dropUp?: boolean;
  /** C1：紧凑文本形态（聊天 Composer 内"模型名 + 下拉"样式） */
  compact?: boolean;
}

/**
 * M7 Batch 3：模型选择下拉（聊天/规划共用）。
 *
 * <p>数据源 GET /api/v1/models（后端仅返回 enabled+selectable）；首项“智能默认”
 * 表示不传 model（走后端角色默认）；加载失败 toast 并仅保留默认项，不阻断页面。</p>
 */
export function ModelSelector({ value, onChange, dropUp = false, compact = false }: ModelSelectorProps) {
  const [options, setOptions] = useState<{ value: string; label: string }[]>([SMART_OPTION]);

  useEffect(() => {
    let cancelled = false;
    modelApi.list()
      .then((res) => {
        if (cancelled) return;
        const models = res.data.data || [];
        if (models.length > 0) {
          setOptions([
            SMART_OPTION,
            ...models.map((m) => ({ value: m.key, label: m.displayName || m.key })),
          ]);
        }
      })
      .catch((err) => {
        if (!cancelled) {
          toast.error('模型列表加载失败: ' + getErrorMessage(err));
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <PagedSingleSelect
      value={value || undefined}
      onChange={(v) => onChange(v ?? '')}
      options={options}
      placeholder="智能默认"
      defaultPageSize={8}
      dropUp={dropUp}
      compact={compact}
    />
  );
}
