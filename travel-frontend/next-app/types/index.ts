// 行程相关类型定义

export interface ItineraryGenerateRequest {
  destination: string;
  days: number;
  budget?: number;
  interests?: string[];
  party?: string;
  startDate?: string;
  clientRequestId: string;
}

export interface DayPlan {
  day: number;
  date?: string;
  summary: string;
  attractions: AttractionVisit[];
  transportMode?: string;
  hotelSuggestion?: string;
}

export interface AttractionVisit {
  name: string;
  timeSlot: string;
  cost?: number;
  notes?: string;
}

export interface MindmapSection {
  title: string;
  items: string[];
}

export interface MindmapData {
  title: string;
  destination?: string;
  days?: string;
  budget?: string;
  sections: MindmapSection[];
}

export interface ItineraryResponse {
  id: number;
  title: string;
  destination: string;
  days: number;
  dayPlans?: DayPlan[];
  estimatedCost?: number;
  mindmap?: MindmapData;
  generatedAt?: string;
}

export interface PageResult<T> {
  list: T[];
  total: number;
  page: number;
  size: number;
  totalPages: number;
}

// 聊天相关
export interface ChatMessage {
  id?: number;
  sessionId: string;
  role: string;
  content: string;
  tokens?: number;
  createdAt?: string;
}

export interface ChatSession {
  id: number;
  sessionId: string;
  userId: number;
  title: string;
  status: string;
  createdAt: string;
}

export interface ChatResponse {
  sessionId: string;
  response: string;
  itineraryId?: number;
  tokens?: number;
}

// 景点相关
export interface Attraction {
  id: number;
  name: string;
  city: string;
  type: string;
  description: string;
  ticketPrice: number;
  freeEntry: number;
  rating: number;
  tags: string;
  recommendedDuration: string;
  imageUrl?: string;
}

export interface SearchResult {
  docId: string;
  title: string;
  snippet: string;
  score: number;
  keywords?: string[];
  source: string;
}

// 用户相关
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  userId: number;
  username: string;
}

// 统一响应
export interface R<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}
