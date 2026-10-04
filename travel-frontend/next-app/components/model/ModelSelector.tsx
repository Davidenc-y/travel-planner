'use client';

import { useCallback, useMemo } from 'react';
import { PagedSingleSelect } from '@/components/ui/paged-options';
import { modelApi } from '@/lib/api';
import { useApiQuery } from '@/lib/use-api-query';
import type { ModelOption } from '@/types';

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
 * 表示不传 model（走后端角色默认）。AW-8：取数 useApiQuery 化（cacheKey 'chat:models'
 * 与预取键同名合流，挂载即命中预取种子/缓存秒开）；加载失败静默仅保留默认项，不阻断页面。</p>
 */
export function ModelSelector({ value, onChange, dropUp = false, compact = false }: ModelSelectorProps) {
  const { data: models } = useApiQuery<ModelOption[]>(
    useCallback(() => modelApi.list().then((res) => res.data.data || []), []),
    [],
    { cacheKey: 'chat:models', staleMs: 600_000 }
  );
  const options = useMemo(
    () => [
      SMART_OPTION,
      ...(models && models.length > 0
        ? models.map((m) => ({ value: m.key, label: m.displayName || m.key }))
        : []),
    ],
    [models]
  );

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
