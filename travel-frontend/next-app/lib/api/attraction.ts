import type { R } from '@/types';
import { knowledgeApi } from './http';

// ==================== Attractions ====================
export const attractionApi = {
  list: (city?: string, type?: string, page = 1, size = 10) =>
    knowledgeApi.get<R<import('@/types').PageResult<import('@/types').Attraction>>>('/api/v1/attractions', { params: { city, type, page, size } }),
  /** M5-1：全部城市列表（“浏览全部”下拉动态数据源） */
  listCities: () =>
    knowledgeApi.get<R<string[]>>('/api/v1/attractions/cities'),
  getById: (id: number) =>
    knowledgeApi.get<R<import('@/types').Attraction>>(`/api/v1/attractions/${id}`),
  search: (query: string, ragType = 'hybrid', topK = 10) =>
    knowledgeApi.post<R<import('@/types').SearchResult[]>>('/api/v1/attractions/search', { query, ragType, topK }),
};
