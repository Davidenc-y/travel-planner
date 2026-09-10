import type { R } from '@/types';
import { planningApi } from './http';

// ==================== Models（M7 Batch 3：模型清单） ====================
export const modelApi = {
  /** 前端可选模型清单（后端仅返回 enabled 且 selectable；embedding/rerank 不可选） */
  list: () =>
    planningApi.get<R<import('@/types').ModelOption[]>>('/api/v1/models'),
};
