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
}

/**
 * 消费一条 SSE 流；HTTP 非 2xx 时抛出带 `response.data` 的错误（供 40904 重试识别）。
 * 业务错误事件通过 handlers.onError 抛出（由调用方统一捕获）。
 */
export async function consumeSseStream(
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

function handleFrame(raw: string, handlers: SseStreamHandlers): void {
  let event = 'message';
  let data = '';
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data += line.slice(5).trim();
    }
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
