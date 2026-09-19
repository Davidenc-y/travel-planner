/**
 * M6：SSE 增量解析（fetch + ReadableStream + TextDecoder）。
 *
 * 说明：EventSource 不支持 POST，因此使用 fetch 流式读取；
 * 服务端事件帧为 `event:<name>\ndata:<json>\n\n`。
 */

export interface StreamThinkingPayload {
  stage?: string;
  message?: string;
}

export interface StreamTokenPayload {
  text?: string;
}

export interface StreamDonePayload {
  sessionId?: string;
  messageId?: number;
  tokens?: number;
  sessionTitle?: string;
  replayed?: boolean;
  // M23（P-D）：锚定询问（AI 生成新规划且会话无可选规划）
  suggestion?: { type: string; itineraryId: number; title: string };
  // M25（E4 收尾）：偏好目的地 vs 锚定目的地冲突
  preferenceConflict?: { preferredDestination: string; anchoredDestination: string; source?: string };
  // M26-F3：本轮有效约束回写（来自行程约束列；空字段由后端过滤）
  preferenceSync?: {
    destination?: string;
    days?: number;
    budget?: string;
    party?: string;
    interests?: string[];
    startDate?: string;
  };
  // M6-16：行程流式 done（/itineraries/generate/stream）
  itineraryId?: number;
  status?: string;
  destination?: string;
  days?: number;
  estimatedCost?: number;
}

export interface StreamErrorPayload {
  code?: number;
  message?: string;
}

export interface SseStreamHandlers {
  onThinking?: (payload: StreamThinkingPayload) => void;
  onToken?: (payload: StreamTokenPayload) => void;
  onDone?: (payload: StreamDonePayload) => void;
  onError?: (payload: StreamErrorPayload) => void;
  /** A-P2/P1：记录最近收到的事件 id（断线续传 Last-Event-ID 数据源） */
  onId?: (id: string) => void;
}

/**
 * 消费一条 SSE 流；HTTP 非 2xx 时抛出带 `response.data` 的错误（供 40904 重试识别）。
 * 业务错误事件通过 handlers.onError 抛出（由调用方统一捕获）。
 * G-5：网络中断（TypeError）自动重连——指数退避 1s/2s/4s 最多 3 次。
 */
export async function consumeSseStream(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  signal: AbortSignal,
  handlers: SseStreamHandlers,
): Promise<void> {
  let retryCount = 0;
  const MAX_RETRIES = 3;

  const attempt = async (): Promise<void> => {
    try {
      return await consumeSseStreamOnce(url, body, headers, signal, handlers);
    } catch (err) {
      // G-5：仅网络中断（TypeError = fetch 网络层错误）重连，业务错误/主动中止不重试
      if (retryCount < MAX_RETRIES && err instanceof TypeError && !signal.aborted) {
        retryCount++;
        const delay = Math.min(1000 * Math.pow(2, retryCount), 8000);
        console.warn(`[SSE] 连接中断，${delay}ms 后第 ${retryCount}/${MAX_RETRIES} 次重连...`);
        await new Promise((r) => setTimeout(r, delay));
        if (signal.aborted) return; // 重连等待期间被用户中止
        return attempt();
      }
      throw err;
    }
  };

  return attempt();
}

/** 单次 SSE 连接（原 consumeSseStream 主体，由 G-5 重连包装器调用） */
async function consumeSseStreamOnce(
  url: string,
  body: Record<string, unknown>,
  headers: Record<string, string>,
  signal: AbortSignal,
  handlers: SseStreamHandlers,
): Promise<void> {
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...headers,
    },
    body: JSON.stringify(body),
    signal,
  });
  if (!res.ok || !res.body) {
    const err: any = new Error(`流式请求失败（HTTP ${res.status}）`);
    try {
      const data = await res.json();
      err.response = { data };
    } catch {
      // 非 JSON 响应保持原始错误
    }
    throw err;
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let frameEnd = buffer.indexOf('\n\n');
    while (frameEnd >= 0) {
      const frame = buffer.slice(0, frameEnd);
      buffer = buffer.slice(frameEnd + 2);
      handleFrame(frame, handlers);
      frameEnd = buffer.indexOf('\n\n');
    }
  }
  if (buffer.trim()) {
    handleFrame(buffer, handlers);
  }
}

/** B0/PE-01：导出供单测（行为不变，仅可见性） */
export function handleFrame(raw: string, handlers: SseStreamHandlers): void {
  let event = 'message';
  let data = '';
  let id = '';
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data += line.slice(5).trim();
    } else if (line.startsWith('id:')) {
      id = line.slice(3).trim();
    }
  }
  if (id) {
    handlers.onId?.(id);
  }
  if (!data) return;
  let payload: any;
  try {
    payload = JSON.parse(data);
  } catch {
    return;
  }
  switch (event) {
    case 'thinking':
      handlers.onThinking?.(payload);
      break;
    case 'token':
      handlers.onToken?.(payload);
      break;
    case 'done':
      handlers.onDone?.(payload);
      break;
    case 'error':
      // 业务错误由调用方处理（如 40904 重试、其余回退 JSON）
      handlers.onError?.(payload);
      break;
    default:
      break;
  }
}
