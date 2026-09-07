'use client';

import { useCallback, useEffect, useRef, useSyncExternalStore } from 'react';
import { chatApi, getErrorMessage, httpErrorCode, isAbortError } from '@/lib/api';
import { ERROR_CODE } from '@/lib/constants';

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

export interface AnchorSuggestion {
  type: string;
  itineraryId: number;
  title: string;
}

export interface StreamedResult {
  text: string;
  sessionTitle?: string;
  /** B3/09 C-07：done 事件携带的本轮 token 数（后端已有字段，此前被丢弃） */
  tokens?: number;
  /** M10-1b：业务错误已在 hook 内处理（如 40303 已提示+气泡），调用方不再兜底 */
  handled?: boolean;
  suggestion?: AnchorSuggestion;
  /** M25（E4 收尾）/M28-4：偏好-行程目的地冲突（source=anchor|itinerary，供提示卡） */
  preferenceConflict?: { preferredDestination: string; anchoredDestination: string; source?: string };
  /** M26-F3：本轮有效约束回写（供偏好标签动态同步） */
  preferenceSync?: {
    destination?: string;
    days?: number;
    budget?: string;
    party?: string;
    interests?: string[];
    startDate?: string;
  };
}

/** M10-1b：业务错误展示回调（page 只注入能力，不承载提示/气泡拼装逻辑） */
export interface ChatStreamErrorHandlers {
  onToastError?: (message: string) => void;
  onAssistantError?: (sid: string, content: string) => void;
}

/** M28-10：离场完成结果——页面切走期间完成的轮次暂存，回场后补收尾 */
export type AwayStreamResult = StreamedResult & { sid: string };

const EMPTY_STATES: Record<string, StreamState> = {};

function idleState(): StreamState {
  return { phase: 'idle', thinkingLines: [], streamingText: '' };
}

// ===== M28-10：模块级流状态存储——SSE 生命周期与状态脱离页面组件 =====
//
// chat → itinerary 等路由切换会卸载聊天页组件；此前流状态挂在组件内
// （useState/useRef），卸载 effect 主动 abort 在途 SSE，思考被硬中断。
// 提升为模块级单例后：路由切换仅取消订阅（流继续，回场即见），仅
// 浏览器刷新/关闭（连接随页面销毁，后端 cancellation 兜底）与用户
// 主动点停止会真正中断。
const store = {
  states: EMPTY_STATES as Record<string, StreamState>,
  listeners: new Set<() => void>(),
  // M6-5：待展示文本队列与揭示定时器（模块级，页面不在场也持续消费）
  pending: '',
  revealTimer: null as ReturnType<typeof setInterval> | null,
  // B3/09 C-03：按会话采集 thinking 文案（供轮次完成后生成执行过程摘要）
  thinking: {} as Record<string, string[]>,
  // M6：在途 SSE 的 abort 手柄（仅用户主动停止使用）
  abort: null as AbortController | null,
  /** 最新挂载实例注入的"当前会话"读取器（离场期间保留最后值，reveal 判定用） */
  sidsGetter: null as (() => string | null) | null,
  /** 活动实例标识（0=页面不在场）；发起轮次捕获的标识不再活动 → 完成时暂存离场结果 */
  instanceId: 0,
  awayResults: new Map<string, AwayStreamResult>(),
};

let instanceSeq = 0;

function commitStates(next: Record<string, StreamState>) {
  store.states = next;
  store.listeners.forEach((l) => l());
}

function subscribeStore(listener: () => void): () => void {
  store.listeners.add(listener);
  return () => store.listeners.delete(listener);
}

function getStoreSnapshot(): Record<string, StreamState> {
  return store.states;
}

function getServerStoreSnapshot(): Record<string, StreamState> {
  return EMPTY_STATES;
}

function mSetStreamState(sid: string, updater: (prev: StreamState) => StreamState) {
  commitStates({ ...store.states, [sid]: updater(store.states[sid] ?? idleState()) });
}

function mClearStreamState(sid: string) {
  const next = { ...store.states };
  delete next[sid];
  commitStates(next);
}

/** 当前可见会话（最新挂载实例优先；离场期间回退发起实例的最后值） */
function sidsNow(fallback: () => string | null): string | null {
  return store.sidsGetter ? store.sidsGetter() : fallback();
}

// M6-5：停止逐字揭示定时器
function stopRevealTimer() {
  if (store.revealTimer !== null) {
    clearInterval(store.revealTimer);
    store.revealTimer = null;
  }
}

// M6-5：立即把剩余待展示文本全部渲染（reduced-motion / 收尾兜底）
function flushPendingStream(fallback: () => string | null) {
  if (store.pending !== '') {
    const rest = store.pending;
    store.pending = '';
    const sid = sidsNow(fallback);
    if (sid) {
      mSetStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + rest }));
    }
  }
  stopRevealTimer();
}

// M6-5：启动逐字揭示；reduced-motion 用户直接整体展示
function startRevealTimer(fallback: () => string | null) {
  if (store.revealTimer !== null) return;
  if (typeof window !== 'undefined'
    && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    flushPendingStream(fallback);
    return;
  }
  store.revealTimer = setInterval(() => {
    if (store.pending === '') {
      stopRevealTimer();
      return;
    }
    const chunk = store.pending.slice(0, REVEAL_CHARS_PER_TICK);
    store.pending = store.pending.slice(REVEAL_CHARS_PER_TICK);
    const sid = sidsNow(fallback);
    if (sid) {
      mSetStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + chunk }));
    }
  }, REVEAL_INTERVAL_MS);
}

// M6-5：等待待展示队列清空（done 后仍把剩余字符逐字展示完再收尾）
function waitForRevealComplete(fallback: () => string | null): Promise<void> {
  return new Promise((resolve) => {
    const started = Date.now();
    const timer = setInterval(() => {
      if (store.pending === '') {
        clearInterval(timer);
        stopRevealTimer();
        resolve();
      } else if (Date.now() - started > REVEAL_WAIT_TIMEOUT_MS) {
        clearInterval(timer);
        flushPendingStream(fallback);
        resolve();
      }
    }, 50);
  });
}

/** M28-10：测试复位（模块单例跨用例持续，beforeEach 显式清空） */
export function __resetChatStreamStoreForTests() {
  stopRevealTimer();
  commitStates(EMPTY_STATES);
  store.pending = '';
  store.thinking = {};
  store.abort = null;
  store.sidsGetter = null;
  store.instanceId = 0;
  store.awayResults.clear();
}

/**
 * M6-58/T10：SSE 流式消费 + streamStates 按会话隔离 + reveal 逐字队列。
 *
 * <p>M28-10：状态与流生命周期提升为模块级（见 store），本 hook 变为薄订阅层——
 * 路由切换卸载组件不再中断在途 SSE；页面不在场期间完成的轮次经 awayResults
 * 暂存，回场后由 takeAwayResult 补收尾。</p>
 *
 * @param getCurrentSid 返回当前可见会话 id（供后台会话直接累积、前台进 reveal 队列）
 */
export function useChatStream(
  getCurrentSid: () => string | null,
  errorHandlers?: ChatStreamErrorHandlers,
) {
  const streamStates = useSyncExternalStore(
    subscribeStore, getStoreSnapshot, getServerStoreSnapshot);

  // M28-10：getCurrentSid 经 ref 读取——调用方通常每渲染传新箭头函数，
  // 直接进 effect 依赖会频繁重跑（instanceId 持续递增，在场轮次被误判离场）
  const getSidsRef = useRef(getCurrentSid);
  getSidsRef.current = getCurrentSid;

  // M28-10：挂载即接管"当前会话"读取器并标记活动实例；卸载不 abort、
  // 不清理流（保留 sidsGetter 最后值供离场 reveal 判定）
  useEffect(() => {
    const id = ++instanceSeq;
    store.instanceId = id;
    store.sidsGetter = () => getSidsRef.current();
    return () => {
      if (store.instanceId === id) store.instanceId = 0;
    };
  }, []);

  /** M6：SSE 流式发送（40904 同键 3s 退避，最多 4 次；业务 error 事件同样重试） */
  const sendStreamWithRetry = useCallback(async (
    sid: string,
    text: string,
    key: string,
    model?: string,
    anchorIds?: number[],
    preferences?: Record<string, unknown>,
  ): Promise<StreamedResult> => {
    const maxAttempts = 4;
    let acc = '';
    let lastId = '';
    // M28-10：发起实例标识——完成时若页面已换实例（路由切走又回来），
    // 原实例的 setState 全部失效，结果转入 awayResults 由新实例补收尾
    const myInstance = store.instanceId;
    const doneState: {
      sessionTitle?: string;
      tokens?: number;
      suggestion?: { type: string; itineraryId: number; title: string };
      preferenceConflict?: { preferredDestination: string; anchoredDestination: string; source?: string };
      preferenceSync?: {
        destination?: string;
        days?: number;
        budget?: string;
        party?: string;
        interests?: string[];
        startDate?: string;
      };
    } = {};
    store.thinking[sid] = [];
    const stashIfPageGone = (result: StreamedResult) => {
      const pageAlive = store.instanceId !== 0 && store.instanceId === myInstance;
      if (!pageAlive) {
        store.awayResults.set(sid, { ...result, sid });
      }
    };
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const controller = new AbortController();
      store.abort = controller;
      try {
        await chatApi.sendMessageStream(sid, text, key, controller.signal, {
          onThinking: (p) => {
            if (p.message) {
              const prevLines = store.thinking[sid] ?? [];
              if (!prevLines.includes(p.message)) {
                store.thinking[sid] = [...prevLines, p.message];
              }
            }
            mSetStreamState(sid, (s) => ({
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
            if (sidsNow(getCurrentSid) === sid) {
              // 当前可见会话：进待展示队列逐字揭示
              store.pending += p.text;
              mSetStreamState(sid, (s) => ({ ...s, phase: 'streaming' }));
              startRevealTimer(getCurrentSid);
            } else {
              // 后台会话：直接累积，切回时整体可见（不逐字揭示）
              mSetStreamState(sid, (s) => ({
                ...s,
                phase: 'streaming',
                streamingText: s.streamingText + p.text,
              }));
            }
          },
          onDone: (p) => {
            doneState.sessionTitle = p.sessionTitle;
            doneState.tokens = p.tokens;
            doneState.suggestion = p.suggestion;
            doneState.preferenceConflict = p.preferenceConflict;
            doneState.preferenceSync = p.preferenceSync;
          },
          onId: (id) => {
            lastId = id;
          },
          onError: (p) => {
            const e: any = new Error(p.message || '流式处理失败');
            e.code = p.code;
            throw e;
          },
        }, lastId || undefined, model, anchorIds, preferences);
        // M6-5：流结束不代表展示结束——等逐字揭示完成后才返回最终文本
        await waitForRevealComplete(getCurrentSid);
        const result: StreamedResult = {
          text: acc,
          sessionTitle: doneState.sessionTitle,
          tokens: doneState.tokens,
          suggestion: doneState.suggestion,
          preferenceConflict: doneState.preferenceConflict,
          preferenceSync: doneState.preferenceSync,
        };
        stashIfPageGone(result);
        return result;
      } catch (err: unknown) {
        if (isAbortError(err)) throw err;
        const code = httpErrorCode(err);
        if (code === 40904 && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        // M10-1b：40303 额度不足——本 hook 统一 toast + assistant 气泡后收尾，
        // page 不再重复拼装（不重试、不回退 JSON）
        if (code === ERROR_CODE.MODEL_QUOTA_EXCEEDED) {
          const quotaText = getErrorMessage(err);
          errorHandlers?.onToastError?.(quotaText);
          errorHandlers?.onAssistantError?.(sid, `⚠️ ${quotaText}`);
          const handled: StreamedResult = { text: '', handled: true };
          stashIfPageGone(handled);
          return handled;
        }
        // P1：中途断线（网络错误、无业务码）且已收到部分内容 → 同键 + Last-Event-ID 续传
        if (!code && lastId && acc && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 1000));
          continue;
        }
        throw err;
      } finally {
        if (store.abort === controller) {
          store.abort = null;
        }
      }
    }
    throw new Error('发送超时，请稍后重试');
  }, [getCurrentSid, errorHandlers]);

  /** M6：主动停止当前在途 SSE（handleStop 用；仅用户主动停止） */
  const abortStream = useCallback(() => {
    store.abort?.abort();
  }, []);

  /** M6-48：切换会话——停止当前会话逐字揭示并清空待展示队列（不中断后端流） */
  const stopRevealForSwitch = useCallback(() => {
    stopRevealTimer();
    store.pending = '';
  }, []);

  /** B3/09 C-03：读取指定会话本轮 thinking 文案（轮次完成后、clearStream 前调用） */
  const getThinkingLines = useCallback((sid: string): string[] => store.thinking[sid] ?? [], []);

  /** 轮次收尾清理：清流态 + 停揭示 + 清队列与 abort 引用 */
  const clearStream = useCallback((sid: string) => {
    mClearStreamState(sid);
    stopRevealTimer();
    store.pending = '';
    delete store.thinking[sid];
    store.abort = null;
  }, []);

  /** M28-10：消费离场完成结果（页面挂载/切会话时机调用，一次性） */
  const takeAwayResult = useCallback((sid: string): AwayStreamResult | undefined => {
    const r = store.awayResults.get(sid);
    if (r) store.awayResults.delete(sid);
    return r;
  }, []);

  return {
    streamStates,
    setStreamState: mSetStreamState,
    clearStreamState: mClearStreamState,
    sendStreamWithRetry,
    abortStream,
    stopRevealForSwitch,
    clearStream,
    getThinkingLines,
    takeAwayResult,
  };
}
