import type { R } from '@/types';
import { planningApi } from './http';

/** M25（E5）：行程分享 API（POST 需认证；GET 公开只读）。 */
export const shareApi = {
  createShare: (itineraryId: number) =>
    planningApi.post<R<{ token: string }>>(`/api/v1/itineraries/${itineraryId}/share`),
  getShared: (token: string) =>
    planningApi.get<R<import('@/types').ItineraryResponse>>(
      `/api/v1/share/${encodeURIComponent(token)}`,
    ),
};
