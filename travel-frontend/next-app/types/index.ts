// 行程相关类型定义

export interface ItineraryGenerateRequest {
  destination: string;
  days: number;
  budget?: number;
  interests?: string[];
  party?: string;
  startDate?: string;
  clientRequestId: string;
  /** F87：可选关联会话（存在时行程知识写入该会话的 session_context） */
  sessionId?: string;
  /** M7 Batch 3：请求级模型（可选；null=角色默认） */
  model?: string;
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
  /** M4-9：行程状态（GENERATING/FAILED/GENERATED…） */
  status?: string;
  /** M6-52：是否可继续生成（FAILED 或僵尸 GENERATING） */
  resumable?: boolean;
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
  /** M5-1：首条消息自动生成标题时返回；其余场景为 undefined */
  sessionTitle?: string;
}

/** M6-42：轮次状态查询结果（前端刷新后恢复"执行已中断 + 重试"入口） */
export interface TurnStatus {
  status: string | null;
  resumable: boolean;
  userMessage?: string;
}

/** M6-47：会话最近可恢复中断轮次（浏览器刷新/重进会话时后端权威查询） */
export interface LatestInterruptedTurn {
  clientMessageId: string | null;
  userMessage?: string;
  resumable: boolean;
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
  /** F121/P1：检索结果带图（ES/Milvus 返回；无图为空） */
  imageUrl?: string;
  source: string;
}

// 用户相关
export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  userId: number;
  username: string;
}

/** F121：当前用户资料（/api/v1/users/me） */
export interface UserInfo {
  id: number;
  username: string;
  avatar?: string | null;
  email?: string | null;
  phone?: string | null;
}

// 统一响应
export interface R<T> {
  code: number;
  message: string;
  data: T;
  timestamp: number;
}

/** M7 Batch 3：前端模型清单条目（GET /api/v1/models，仅 enabled+selectable） */
export interface ModelOption {
  key: string;
  displayName: string;
  provider: string;
  selectable: boolean;
}
