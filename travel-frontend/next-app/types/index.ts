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
/** B3（09 C-03/C-07）：轮次执行过程（前端本地采集，非后端字段） */
export interface MessageProcess {
  /** thinking 事件文案（按到达顺序） */
  stages: string[];
  /** thinking 首事件到 done 的前端耗时（ms） */
  elapsedMs: number;
}

export interface ChatMessage {
  id?: number;
  sessionId: string;
  role: string;
  content: string;
  tokens?: number;
  createdAt?: string;
  /** PE-03/F-13：乐观消息的本地稳定 key（后端 id 到达前用作 React key） */
  localKey?: string;
  /** B3/09 C-03：已完成轮次的执行过程（折叠摘要用） */
  process?: MessageProcess;
  /** B3/09 C-09：JSON 兜底路径返回的行程 id（SSE done 暂无该字段，就绪后自动获得） */
  itineraryId?: number;
}

export interface ChatSession {
  id: number;
  sessionId: string;
  userId: number;
  title: string;
  /** R2/A5：会话状态（t_chat_session 仅写入 ACTIVE/ARCHIVED，struct/14 核验） */
  status: 'ACTIVE' | 'ARCHIVED';
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

// U1：个人中心使用统计（GET /api/v1/users/me/usage-stats，数据源 t_agent_trace）
export interface DailyToken {
  date: string; // yyyy-MM-dd
  tokens: number;
}

export interface DailyModelToken {
  date: string;
  model: string;
  tokens: number;
}

export interface ModelUsage {
  model: string;
  tokens: number;
}

export interface UsageStats {
  totalTokens: number;
  peakDayTokens: number;
  longestTurnMs: number;
  currentStreakDays: number;
  longestStreakDays: number;
  daily: DailyToken[]; // 近 365 天逐日总量（热力图）
  trend: DailyModelToken[]; // 近 range 天逐日×模型（趋势图）
  modelUsage: ModelUsage[]; // 近 range 天按模型（环形图）
}

/** M7 Batch 3：前端模型清单条目（GET /api/v1/models，仅 enabled+selectable） */
export interface ModelOption {
  key: string;
  displayName: string;
  provider: string;
  selectable: boolean;
}
