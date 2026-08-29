'use client';

import { useCallback, useRef, useState } from 'react';
import { chatApi } from '@/lib/api';

// M6-5：逐字揭示节奏——后端可能一次性爆发式发送全部分块，
// 前端按固定节奏消费待展示队列，保证“逐字直到完全展示”。
const REVEAL_INTERVAL_MS = 24;
const REVEAL_CHARS_PER_TICK = 3;
const REVEAL_WAIT_TIMEOUT_MS = 120_000;

// M6-48：单会话流式 UI 状态（切换会话不中断后端思考，各会话独立维护）
export interface StreamState {
  phase: 'idle' | 'thinking' | 'streaming';
  thinkingLines: string[];
  streamingText: string;
}

export interface StreamedResult {
  text: string;
  sessionTitle?: string;
}

/**
 * M6-58/T10：SSE 流式消费 + streamStates 按会话隔离 + reveal 逐字队列。
 *
 * <p>从 chat/page.tsx 迁出（M5-1 竞态、M6-5 逐字揭示、M6-48 会话隔离、
 * M6-49 置顶联动依赖方、M4-9/M6 幂等退避与断线续传语义全部保留）。</p>
 *
 * @param getCurrentSid 返回当前可见会话 id（供后台会话直接累积、前台进 reveal 队列）
 */
export function useChatStream(getCurrentSid: () => string | null) {
  const [streamStates, setStreamStates] = useState<Record<string, StreamState>>({});
  const streamAbortRef = useRef<AbortController | null>(null);
  // M6-5：待展示文本队列与揭示定时器
  const pendingStreamRef = useRef('');
  const revealTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const setStreamState = useCallback(
    (sid: string, updater: (prev: StreamState) => StreamState) => {
      setStreamStates((prev) => ({
        ...prev,
        [sid]: updater(prev[sid] ?? { phase: 'idle', thinkingLines: [], streamingText: '' }),
      }));
    },
    [],
  );

  const clearStreamState = useCallback((sid: string) => {
    setStreamStates((prev) => {
      const next = { ...prev };
      delete next[sid];
      return next;
    });
  }, []);

  // M6-5：停止逐字揭示定时器
  const stopReveal = useCallback(() => {
    if (revealTimerRef.current !== null) {
      clearInterval(revealTimerRef.current);
      revealTimerRef.current = null;
    }
  }, []);

  // M6-5：立即把剩余待展示文本全部渲染（reduced-motion / 收尾兜底）
  const flushPendingStream = useCallback(() => {
    if (pendingStreamRef.current !== '') {
      const rest = pendingStreamRef.current;
      pendingStreamRef.current = '';
      const sid = getCurrentSid();
      if (sid) {
        setStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + rest }));
      }
    }
    stopReveal();
  }, [getCurrentSid, setStreamState, stopReveal]);

  // M6-5：启动逐字揭示；reduced-motion 用户直接整体展示
  const startReveal = useCallback(() => {
    if (revealTimerRef.current !== null) return;
    if (typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      flushPendingStream();
      return;
    }
    revealTimerRef.current = setInterval(() => {
      if (pendingStreamRef.current === '') {
        stopReveal();
        return;
      }
      const chunk = pendingStreamRef.current.slice(0, REVEAL_CHARS_PER_TICK);
      pendingStreamRef.current = pendingStreamRef.current.slice(REVEAL_CHARS_PER_TICK);
      const sid = getCurrentSid();
      if (sid) {
        setStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + chunk }));
      }
    }, REVEAL_INTERVAL_MS);
  }, [flushPendingStream, getCurrentSid, setStreamState, stopReveal]);

  // M6-5：等待待展示队列清空（done 后仍把剩余字符逐字展示完再收尾）
  const waitForRevealComplete = useCallback((): Promise<void> =>
    new Promise((resolve) => {
      const started = Date.now();
      const timer = setInterval(() => {
        if (pendingStreamRef.current === '') {
          clearInterval(timer);
          stopReveal();
          resolve();
        } else if (Date.now() - started > REVEAL_WAIT_TIMEOUT_MS) {
          clearInterval(timer);
          flushPendingStream();
          resolve();
        }
      }, 50);
    }), [flushPendingStream, stopReveal]);

  /** M6：SSE 流式发送（40904 同键 3s 退避，最多 4 次；业务 error 事件同样重试） */
  const sendStreamWithRetry = useCallback(async (
    sid: string,
    text: string,
    key: string,
    model?: string,
  ): Promise<StreamedResult> => {
    const maxAttempts = 4;
    let acc = '';
    let lastId = '';
    const doneState: { sessionTitle?: string } = {};
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const controller = new AbortController();
      streamAbortRef.current = controller;
      try {
        await chatApi.sendMessageStream(sid, text, key, controller.signal, {
          onThinking: (p) => {
            setStreamState(sid, (s) => ({
              ...s,
              phase: 'thinking',
              thinkingLines: p.message && !s.thinkingLines.includes(p.message)
                ? [...s.thinkingLines, p.message]
                : s.thinkingLines,
            }));
          },
          onToken: (p) => {
            if (!p.text) return;
            acc += p.text;
            if (getCurrentSid() === sid) {
              // 当前可见会话：进待展示队列逐字揭示
              pendingStreamRef.current += p.text;
              setStreamState(sid, (s) => ({ ...s, phase: 'streaming' }));
              startReveal();
            } else {
              // 后台会话：直接累积，切回时整体可见（不逐字揭示）
              setStreamState(sid, (s) => ({
                ...s,
                phase: 'streaming',
                streamingText: s.streamingText + p.text,
              }));
            }
          },
          onDone: (p) => {
            doneState.sessionTitle = p.sessionTitle;
          },
          onId: (id) => {
            lastId = id;
          },
          onError: (p) => {
            const e: any = new Error(p.message || '流式处理失败');
            e.code = p.code;
            throw e;
          },
        }, lastId || undefined, model);
        // M6-5：流结束不代表展示结束——等逐字揭示完成后才返回最终文本
        await waitForRevealComplete();
        return { text: acc, sessionTitle: doneState.sessionTitle };
      } catch (err: any) {
        if (err?.name === 'AbortError') throw err;
        const code = err?.response?.data?.code ?? err?.code;
        if (code === 40904 && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        // P1：中途断线（网络错误、无业务码）且已收到部分内容 → 同键 + Last-Event-ID 续传
        if (!code && lastId && acc && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 1000));
          continue;
        }
        throw err;
      } finally {
        if (streamAbortRef.current === controller) {
          streamAbortRef.current = null;
        }
      }
    }
    throw new Error('发送超时，请稍后重试');
  }, [getCurrentSid, setStreamState, startReveal, waitForRevealComplete]);

  /** M6：主动停止当前在途 SSE（handleStop/卸载共用） */
  const abortStream = useCallback(() => {
    streamAbortRef.current?.abort();
  }, []);

  /** M6-48：切换会话——停止当前会话逐字揭示并清空待展示队列（不中断后端流） */
  const stopRevealForSwitch = useCallback(() => {
    stopReveal();
    pendingStreamRef.current = '';
  }, [stopReveal]);

  /** 轮次收尾清理：清流态 + 停揭示 + 清队列与 abort 引用 */
  const clearStream = useCallback((sid: string) => {
    clearStreamState(sid);
    stopReveal();
    pendingStreamRef.current = '';
    streamAbortRef.current = null;
  }, [clearStreamState, stopReveal]);

  /** M6：组件卸载时取消在途流并停揭示 */
  const dispose = useCallback(() => {
    streamAbortRef.current?.abort();
    stopReveal();
  }, [stopReveal]);

  return {
    streamStates,
    setStreamState,
    clearStreamState,
    sendStreamWithRetry,
    abortStream,
    stopRevealForSwitch,
    clearStream,
    dispose,
  };
}
