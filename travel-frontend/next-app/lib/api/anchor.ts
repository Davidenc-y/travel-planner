import type { R } from '@/types';
import { planningApi } from './http';

/** M23（E1）：会话锚定 API——GET 回读（含 brief）/ PUT 全量替换（幂等，返回有效集合）。 */
export const anchorApi = {
  getAnchors: (sessionId: string) =>
    planningApi.get<R<import('@/types').AnchorBrief[]>>(
      `/api/v1/chat/sessions/${sessionId}/anchored-itineraries`,
    ),
  replaceAnchors: (sessionId: string, itineraryIds: number[]) =>
    planningApi.put<R<number[]>>(
      `/api/v1/chat/sessions/${sessionId}/anchored-itineraries`,
      { itineraryIds },
    ),
};
