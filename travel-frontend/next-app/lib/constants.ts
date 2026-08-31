/**
 * B5（front_design 06 §6，F-25）：前端常量层。
 * 状态枚举与错误码集中定义（与后端 ErrorCode/状态机口径对齐）；
 * "改哪页收哪页"——本批先接入 chat/itinerary 两页已改动处，其余页面后续迭代迁移。
 */

/** 行程状态（t_itinerary.status；后端实际写入 GENERATED/GENERATING/FAILED 三态） */
export const ITINERARY_STATUS = {
  GENERATING: 'GENERATING',
  GENERATED: 'GENERATED',
  FAILED: 'FAILED',
} as const;

/** R2/A5：行程状态联合类型（由常量反向推导） */
export type ItineraryStatus = (typeof ITINERARY_STATUS)[keyof typeof ITINERARY_STATUS];

/** 聊天会话状态（t_chat_session.status；后端实际写入 ACTIVE/ARCHIVED 两态） */
export const SESSION_STATUS = {
  ACTIVE: 'ACTIVE',
  ARCHIVED: 'ARCHIVED',
} as const;

/** R2/A5：会话状态联合类型 */
export type SessionStatus = (typeof SESSION_STATUS)[keyof typeof SESSION_STATUS];

/** 高频业务错误码（struct/13 附录 B 口径） */
export const ERROR_CODE = {
  /** 模型未找到（前端已选模型被禁用/未注册） */
  MODEL_NOT_FOUND: 40005,
  /** 未登录 */
  UNAUTHORIZED: 40101,
  /** 限流 */
  RATE_LIMITED: 40301,
  /** 越权 / 注入拦截 */
  FORBIDDEN: 40302,
  /** 消息处理中（同 clientMessageId 在途） */
  MESSAGE_PROCESSING: 40904,
  /** 行程生成中（同 clientRequestId） */
  ITINERARY_PROCESSING: 40905,
} as const;

/** 景点类型标签（attractions 页与后端枚举对齐） */
export const ATTRACTION_TYPE_LABELS: Record<string, string> = {
  CULTURE: '文化',
  NATURE: '自然',
  FOOD: '美食',
  SHOPPING: '购物',
  FAMILY: '亲子',
  LEISURE: '休闲',
};

/** 聊天流式 Markdown 渲染开关的 localStorage key（09 C-02 守卫三） */
export const STREAM_MARKDOWN_PREF_KEY = 'travel.chat.stream-markdown';

/** R3（02-11 §10.2-R4）：行程列表 GENERATING 轮询间隔（M6-54 语义：仅生成中轮询，完成即停） */
export const ITINERARY_POLL_INTERVAL_MS = 3000;
