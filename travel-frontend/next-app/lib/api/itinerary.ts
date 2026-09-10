import type { R } from '@/types';
import { consumeSseStream, type SseStreamHandlers } from '../sse';
import { PLANNING_BASE, planningApi } from './http';

// ==================== Itinerary ====================
export const itineraryApi = {
  generate: (data: import('@/types').ItineraryGenerateRequest) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>('/api/v1/itineraries/generate', data),
  /** M6-16：行程流式生成（SSE）——失败由调用方回退 JSON generate */
  generateStream: (
    data: import('@/types').ItineraryGenerateRequest,
    signal: AbortSignal,
    handlers: SseStreamHandlers,
  ) => {
    const headers: Record<string, string> = {};
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) headers.Authorization = `Bearer ${token}`;
    }
    return consumeSseStream(
      `${PLANNING_BASE}/api/v1/itineraries/generate/stream`,
      { ...data },
      headers,
      signal,
      handlers,
    );
  },
  getById: (id: number) =>
    planningApi.get<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}`),
  /** M27（E7）：导出 .ics——二进制下载（fetch 带 Bearer；返回 Blob 供触发保存） */
  exportIcs: async (id: number): Promise<Blob> => {
    const headers: Record<string, string> = {};
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) headers.Authorization = `Bearer ${token}`;
    }
    const res = await fetch(`${PLANNING_BASE}/api/v1/itineraries/${id}/export.ics`, { headers });
    if (!res.ok) {
      throw Object.assign(new Error(`日历导出失败: HTTP ${res.status}`), { status: res.status });
    }
    return res.blob();
  },
  list: (page = 1, size = 10) =>
    planningApi.get<R<import('@/types').PageResult<import('@/types').ItineraryResponse>>>('/api/v1/itineraries', { params: { page, size } }),
  delete: (id: number) =>
    planningApi.delete<R<void>>(`/api/v1/itineraries/${id}`),
  /** M28-6：标题重命名（同值前端已拦截；后端幂等兜底） */
  renameItinerary: (id: number, title: string) =>
    planningApi.patch<R<string>>(`/api/v1/itineraries/${id}/title`, { title }),
  /** M28-13：用户显式修改偏好元数据（同行人/兴趣）→ 行程约束列持久化 */
  updateConstraints: (id: number, payload: { party?: string; interests?: string[] }) =>
    planningApi.patch<R<import('@/types').ItineraryResponse>>(
      `/api/v1/itineraries/${id}/constraints`, payload),
  /** M4-9：断点续跑（仅 FAILED/僵尸 GENERATING 可续；同步等待同 generate） */
  resume: (id: number) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}/resume`),
  /** M11-1：历史版本列表/详情 */
  versions: (id: number) =>
    planningApi.get<R<Array<Record<string, unknown>>>>(`/api/v1/itineraries/${id}/versions`),
  version: (id: number, version: number) =>
    planningApi.get<R<Record<string, unknown>>>(`/api/v1/itineraries/${id}/versions/${version}`),
  /** M15-4：版本切换（选中版本即当前使用，不新增版本；保留旧接口别名） */
  activateVersion: (id: number, version: number) =>
    planningApi.post<R<{ itineraryId: number; version: number; activeVersion: number }>>(
      `/api/v1/itineraries/${id}/versions/${version}/switch`),
  rollbackVersion: (id: number, version: number) =>
    planningApi.post<R<{ itineraryId: number; version: number; activeVersion: number }>>(
      `/api/v1/itineraries/${id}/versions/${version}/rollback`),
  /** M12：行程地图真实路网/住宿锚点（后端代理高德，前端不直连第三方） */
  mapRoutes: (id: number) =>
    planningApi.get<R<import('@/types').MapRouteResponse>>(`/api/v1/itineraries/${id}/map-routes`),
};
