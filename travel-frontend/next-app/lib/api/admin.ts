import type { R } from '@/types';
import { planningApi } from './http';

// ==================== Admin（M11-3：可靠性看板） ====================
export const adminApi = {
  /** 近 N 天可靠性聚合（后端按 travel.admin.user-ids 白名单门控） */
  reliabilityStats: (days = 7) =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/stats', {
      params: { days },
    }),
};
