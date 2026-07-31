import axios, { AxiosInstance } from 'axios';
import type { R } from '@/types';

const PLANNING_BASE = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';

function createClient(baseURL: string): AxiosInstance {
  const client = axios.create({ baseURL, timeout: 60000 });
  client.interceptors.request.use((config) => {
    if (typeof window !== 'undefined') {
      const token = localStorage.getItem('accessToken');
      if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }
      const userId = localStorage.getItem('userId');
      if (userId) {
        config.headers['X-User-Id'] = userId;
      }
    }
    return config;
  });
  client.interceptors.response.use(
    (res) => res,
    (error) => {
      if (error.response?.status === 401 && typeof window !== 'undefined') {
        localStorage.removeItem('accessToken');
        localStorage.removeItem('refreshToken');
        window.location.href = '/login';
      }
      return Promise.reject(error);
    }
  );
  return client;
}

export const planningApi = createClient(PLANNING_BASE);
export const knowledgeApi = createClient(KNOWLEDGE_BASE);

// ==================== Auth ====================
export const authApi = {
  login: (username: string, password: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/login', { username, password }),
  register: (username: string, password: string, email?: string) =>
    planningApi.post<R<{ accessToken: string; refreshToken: string; userId: number; username: string }>>('/api/v1/auth/register', { username, password, email }),
};

// ==================== Itinerary ====================
export const itineraryApi = {
  generate: (data: import('@/types').ItineraryGenerateRequest) =>
    planningApi.post<R<import('@/types').ItineraryResponse>>('/api/v1/itineraries/generate', data),
  getById: (id: number) =>
    planningApi.get<R<import('@/types').ItineraryResponse>>(`/api/v1/itineraries/${id}`),
  list: (userId: number, page = 1, size = 10) =>
    planningApi.get<R<import('@/types').PageResult<import('@/types').ItineraryResponse>>>('/api/v1/itineraries', { params: { userId, page, size } }),
  delete: (id: number) =>
    planningApi.delete<R<void>>(`/api/v1/itineraries/${id}`),
};

// ==================== Chat ====================
export const chatApi = {
  createSession: (userId: number, title?: string) =>
    planningApi.post<R<string>>('/api/v1/chat/sessions', { userId, title }),
  listSessions: (userId: number) =>
    planningApi.get<R<import('@/types').ChatSession[]>>('/api/v1/chat/sessions', { params: { userId } }),
  getHistory: (sessionId: string) =>
    planningApi.get<R<import('@/types').ChatMessage[]>>(`/api/v1/chat/sessions/${sessionId}/history`),
  sendMessage: (sessionId: string, message: string) =>
    planningApi.post<R<import('@/types').ChatResponse>>(`/api/v1/chat/sessions/${sessionId}/messages`, { message }),
};

// ==================== Attractions ====================
export const attractionApi = {
  list: (city?: string, type?: string, page = 1, size = 10) =>
    knowledgeApi.get<R<import('@/types').PageResult<import('@/types').Attraction>>>('/api/v1/attractions', { params: { city, type, page, size } }),
  getById: (id: number) =>
    knowledgeApi.get<R<import('@/types').Attraction>>(`/api/v1/attractions/${id}`),
  search: (query: string, ragType = 'hybrid', topK = 10) =>
    knowledgeApi.post<R<import('@/types').SearchResult[]>>('/api/v1/attractions/search', { query, ragType, topK }),
};
