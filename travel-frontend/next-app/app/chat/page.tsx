'use client';

import { useEffect, useState, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, Send, Plus, MessageSquare, Archive, Copy, ArrowDown, Square, RotateCcw } from 'lucide-react';
import { chatApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ChatMessage, ChatResponse, ChatSession } from '@/types';
import { cn } from '@/lib/utils';
import { ChatMessageContent } from '@/components/feature/chat-message-content';
import { Skeleton } from '@/components/ui/skeleton';
import { takePrefetch } from '@/lib/prefetch';

// M6-5：逐字揭示节奏——后端可能一次性爆发式发送全部分块，
// 前端按固定节奏消费待展示队列，保证“逐字直到完全展示”。
const REVEAL_INTERVAL_MS = 24;
const REVEAL_CHARS_PER_TICK = 3;
const REVEAL_WAIT_TIMEOUT_MS = 120_000;

// M6-36：消息时间戳（同日 HH:mm，跨日 MM-DD HH:mm；本地兜底当前时间）
function formatMessageTime(iso?: string): string {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`;
}

interface InterruptedTurn {
  clientMessageId: string;
  text: string;
}

// M6-48：单会话流式 UI 状态（切换会话不中断后端思考，各会话独立维护）
interface StreamState {
  phase: 'idle' | 'thinking' | 'streaming';
  thinkingLines: string[];
  streamingText: string;
}

function ChatContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // M5-1：草稿按会话隔离（未选中会话时使用 '__new__'）
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [creatingSession, setCreatingSession] = useState(false);
  // M5-1：标题编辑态
  const [editingSessionId, setEditingSessionId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');
  // M5-1：滚动状态（回到底部按钮）
  const [isNearBottom, setIsNearBottom] = useState(true);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const instantScrollRef = useRef(true);
  const nearBottomRef = useRef(true);
  const creatingRef = useRef(false);
  const sendingRef = useRef(false);
  const titleInputRef = useRef<HTMLInputElement>(null);
  const titleSavingRef = useRef(false);
  // M5-1：历史请求竞态防护——切会话/新建会话后，过期历史响应不得覆盖当前消息
  const historySidRef = useRef<string | null>(null);
  const currentSessionRef = useRef<string | null>(null);
  // M5-1：Esc 取消编辑后，输入框卸载触发的 onBlur 不得误保存
  const cancelEditRef = useRef(false);
  // M6-48：流式状态按会话隔离（切会话不中断，切回仍显示思考/输出进度）
  const [streamStates, setStreamStates] = useState<Record<string, StreamState>>({});
  // M6-48：后台会话完成回复 → 会话列表右侧红点提示（点进会话后清除）
  const [completedTurns, setCompletedTurns] = useState<Record<string, boolean>>({});
  const streamAbortRef = useRef<AbortController | null>(null);
  // M6-36：每会话最多一个中断轮次（重试/新消息时清除）
  const [interruptedTurns, setInterruptedTurns] = useState<Record<string, InterruptedTurn>>({});
  const interruptedRef = useRef<Record<string, InterruptedTurn>>({});
  const activeTurnRef = useRef<{ sid: string; key: string; text: string } | null>(null);
  const stopRequestedRef = useRef(false);
  // M6-50：首条消息发送中才真实创建的会话（完成后再拉取列表，避免提前出现）
  const pendingNewSessionRef = useRef<string | null>(null);
  // M6-5：待展示文本队列与揭示定时器
  const pendingStreamRef = useRef('');
  const revealTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const activeDraftKey = currentSessionId ?? '__new__';
  const input = drafts[activeDraftKey] ?? '';
  const currentStreamState = currentSessionId ? streamStates[currentSessionId] : undefined;

  const setInterruptedMap = (
    updater: (prev: Record<string, InterruptedTurn>) => Record<string, InterruptedTurn>,
  ) => {
    setInterruptedTurns((prev) => {
      const next = updater(prev);
      interruptedRef.current = next;
      return next;
    });
  };

  const setStreamState = (
    sid: string,
    updater: (prev: StreamState) => StreamState,
  ) => {
    setStreamStates((prev) => ({
      ...prev,
      [sid]: updater(prev[sid] ?? { phase: 'idle', thinkingLines: [], streamingText: '' }),
    }));
  };

  const clearStreamState = (sid: string) => {
    setStreamStates((prev) => {
      const next = { ...prev };
      delete next[sid];
      return next;
    });
  };

  // M6-49：会话置顶（最后消息时间最新；配合后端排序，发送完成后即时生效）
  const moveSessionToTop = (sid: string) => {
    setSessions((prev) => {
      const idx = prev.findIndex((s) => s.sessionId === sid);
      if (idx <= 0) return prev;
      const next = [...prev];
      const [s] = next.splice(idx, 1);
      return [s, ...next];
    });
  };

  // M6-50：轮次完成后的会话列表收口——新建会话拉取权威列表（标题已由后端
  // 首条消息联动生成）；既有会话仅置顶（避免多余请求）
  const finalizeTurnSession = (sid: string) => {
    if (pendingNewSessionRef.current === sid) {
      pendingNewSessionRef.current = null;
      loadSessions(true);
    } else {
      moveSessionToTop(sid);
    }
  };

  // M6-49：回复完成（成功/兜底）→ 当前会话直接追加，否则红点提示；并置顶会话
  const appendAssistantOrNotify = (sid: string, msg: ChatMessage) => {
    if (currentSessionRef.current === sid) {
      setMessages((prev) => [...prev, msg]);
    } else {
      setCompletedTurns((prev) => ({ ...prev, [sid]: true }));
    }
    finalizeTurnSession(sid);
  };

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    if (userId) loadSessions();
  }, [userId, isAuthenticated]);

  useEffect(() => {
    if (currentSessionId) loadHistory(currentSessionId);
  }, [currentSessionId]);

  // M5-1：同步当前会话到 ref，供异步历史响应做最终归属校验
  useEffect(() => {
    currentSessionRef.current = currentSessionId;
  }, [currentSessionId]);

  // M5-1：进入会话一屏直达底部；新消息时仅在接近底部才平滑滚动
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (instantScrollRef.current) {
      instantScrollRef.current = false;
      el.scrollTo({ top: el.scrollHeight });
      nearBottomRef.current = true;
      setIsNearBottom(true);
      return;
    }
    if (nearBottomRef.current) {
      const reduced = typeof window !== 'undefined'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
      el.scrollTo({ top: el.scrollHeight, behavior: reduced ? 'auto' : 'smooth' });
    }
  }, [messages, sending]);

  // M5-1：标题编辑态聚焦
  useEffect(() => {
    if (editingSessionId) {
      titleInputRef.current?.focus();
      titleInputRef.current?.select();
    }
  }, [editingSessionId]);

  // M6-48：切换会话不再 abort 在途流（后端继续思考）——仅停止当前会话逐字揭示
  useEffect(() => {
    stopReveal();
    pendingStreamRef.current = '';
  }, [currentSessionId]);

  // M6：组件卸载时取消在途流
  useEffect(() => () => {
    streamAbortRef.current?.abort();
    stopReveal();
  }, []);

  const updateNearBottom = () => {
    const el = scrollRef.current;
    if (!el) return;
    const near = el.scrollHeight - el.scrollTop - el.clientHeight <= 40;
    nearBottomRef.current = near;
    setIsNearBottom(near);
  };

  // M6-5：停止逐字揭示定时器
  const stopReveal = () => {
    if (revealTimerRef.current !== null) {
      clearInterval(revealTimerRef.current);
      revealTimerRef.current = null;
    }
  };

  // M6-5：立即把剩余待展示文本全部渲染（reduced-motion / 收尾兜底）
  const flushPendingStream = () => {
    if (pendingStreamRef.current !== '') {
      const rest = pendingStreamRef.current;
      pendingStreamRef.current = '';
      const sid = currentSessionRef.current;
      if (sid) {
        setStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + rest }));
      }
    }
    stopReveal();
  };

  // M6-5：启动逐字揭示；reduced-motion 用户直接整体展示
  const startReveal = () => {
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
      const sid = currentSessionRef.current;
      if (sid) {
        setStreamState(sid, (s) => ({ ...s, streamingText: s.streamingText + chunk }));
      }
    }, REVEAL_INTERVAL_MS);
  };

  // M6-5：等待待展示队列清空（done 后仍把剩余字符逐字展示完再收尾）
  const waitForRevealComplete = (): Promise<void> =>
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
    });

  const loadSessions = async (force = false) => {
    // F102：命中预取缓存则直接展示；M5-1：会话变更后 force 绕过缓存强制刷新
    if (!force) {
      const cached = takePrefetch<ChatSession[]>('chat:sessions');
      if (cached) {
        setSessions(cached);
        setLoading(false);
        return;
      }
    }
    try {
      const res = await chatApi.listSessions(userId!);
      setSessions(res.data.data || []);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  };

  const loadHistory = async (sid: string) => {
    historySidRef.current = sid;
    try {
      const res = await chatApi.getHistory(sid);
      if (historySidRef.current !== sid || currentSessionRef.current !== sid) return;
      instantScrollRef.current = true;
      setMessages(res.data.data || []);
      // M6-47：刷新/切会话后恢复重试入口——浏览器刷新不会触发 handleStop
      // （无本地 key），统一由后端按"INTERRUPTED + 断点存在"权威查询
      chatApi.getLatestInterruptedTurn(sid)
        .then((statusRes) => {
          if (historySidRef.current !== sid || currentSessionRef.current !== sid) return;
          const d = statusRes.data.data;
          if (d?.resumable && d.clientMessageId) {
            setInterruptedMap((prev) => ({
              ...prev,
              [sid]: {
                clientMessageId: d.clientMessageId!,
                text: d.userMessage || '',
              },
            }));
          }
        })
        .catch(() => {});
    } catch {
      if (historySidRef.current !== sid || currentSessionRef.current !== sid) return;
      instantScrollRef.current = true;
      setMessages([]);
    }
  };

  const handleNewSession = async () => {
    if (creatingRef.current) return;
    creatingRef.current = true;
    setCreatingSession(true);
    try {
      // M6-50：点击新会话不立即创建——进入"未创建"编辑态；
      // 首条消息真正发送时才调用后端创建并加入左侧列表
      setCurrentSessionId(null);
      setMessages([]);
      historySidRef.current = null;
      instantScrollRef.current = true;
    } catch {
      // 纯本地状态切换，无后端调用
    } finally {
      creatingRef.current = false;
      setCreatingSession(false);
    }
  };

  /**
   * M4-9：带幂等键的发送——40904（同键处理中）3s 退避同键重试，最多 4 次尝试。
   * 兼容两种返回形态：HTTP 状态对齐（409 抛异常，err.response.data.code）与
   * 业务码双轨（200 + body.code）。M5-1：返回完整响应以消费 sessionTitle。
   */
  const sendMessageWithIdempotency = async (sid: string, text: string, key: string): Promise<ChatResponse> => {
    const maxAttempts = 4;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      try {
        const res = await chatApi.sendMessage(sid, text, key);
        if (res.data.code === 40904 && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        if (res.data.code !== 200) {
          throw new Error(res.data.message || `发送失败(${res.data.code})`);
        }
        return res.data.data;
      } catch (err: any) {
        const code = err?.response?.data?.code;
        if (code === 40904 && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        throw err;
      }
    }
    throw new Error('发送超时，请稍后重试');
  };

  /** M6：SSE 流式发送（40904 同键 3s 退避，最多 4 次；业务 error 事件同样重试） */
  const sendStreamWithRetry = async (
    sid: string,
    text: string,
    key: string,
  ): Promise<{ text: string; sessionTitle?: string }> => {
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
            if (currentSessionRef.current === sid) {
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
        }, lastId || undefined);
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
  };

  const handleSend = async (retrySid?: string, retryText?: string, retryKey?: string) => {
    const isRetry = retrySid != null;
    const text = isRetry ? retryText! : input.trim();
    if (!text || sendingRef.current || creatingRef.current) return;
    const hadNoSession = !isRetry && !currentSessionId;

    // M5-1：初始界面直接发送 → 自动创建会话并进入
    let sid = isRetry ? retrySid! : currentSessionId;
    if (!sid && !isRetry) {
      creatingRef.current = true;
      setCreatingSession(true);
      try {
        const res = await chatApi.createSession(userId!);
        sid = res.data.data;
        // M6-50：标记为"首条消息中创建"——完成后再刷新列表，标题由后端联动生成
        pendingNewSessionRef.current = sid;
        setCurrentSessionId(sid);
        setMessages([]);
        historySidRef.current = null;
        instantScrollRef.current = true;
      } catch (err: any) {
        toast.error('创建会话失败: ' + getErrorMessage(err));
        return;
      } finally {
        creatingRef.current = false;
        setCreatingSession(false);
      }
    }

    // M6-36：发起新消息 → 清除该会话旧断点（重试按钮永久消失）
    if (!isRetry) {
      const prevTurn = interruptedRef.current[sid!];
      if (prevTurn) {
        setInterruptedMap((prev) => {
          const next = { ...prev };
          delete next[sid!];
          return next;
        });
        chatApi.clearBreakpoint(sid!, prevTurn.clientMessageId).catch(() => {});
      }
    }

    if (!isRetry) {
      const userMsg: ChatMessage = {
        sessionId: sid!,
        role: 'user',
        content: text,
        createdAt: new Date().toISOString(),
      };
      // M5-1：使新建会话的 getHistory 空响应失效，避免覆盖乐观追加的用户消息
      historySidRef.current = null;
      setMessages((prev) => [...prev, userMsg]);
      setDrafts((prev) => {
        const next = { ...prev };
        delete next[sid!];
        if (hadNoSession) delete next.__new__;
        return next;
      });
    }
    sendingRef.current = true;
    setSending(true);
    setStreamState(sid!, (s) => ({
      ...s,
      phase: 'thinking',
      thinkingLines: ['已收到，Agent 正在思考…'],
      streamingText: '',
    }));

    // M4-9：消息幂等键——超时/40904 重试携带同键，杜绝重复追加/双跑
    const clientMessageId = isRetry ? retryKey! : crypto.randomUUID();
    activeTurnRef.current = { sid: sid!, key: clientMessageId, text };
    try {
      // M6：优先 SSE 流式；失败自动回退 JSON 端点
      const streamed = await sendStreamWithRetry(sid!, text, clientMessageId);
      const aiMsg: ChatMessage = {
        sessionId: sid!,
        role: 'assistant',
        content: streamed.text,
        createdAt: new Date().toISOString(),
      };
      appendAssistantOrNotify(sid!, aiMsg);
      if (streamed.sessionTitle) {
        setSessions((prev) => prev.map((s) =>
          s.sessionId === sid ? { ...s, title: streamed.sessionTitle! } : s));
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return; // 主动取消（切换会话/卸载）
      const code = err?.response?.data?.code ?? err?.code;
      if (code === 40904) {
        toast.error('发送超时，请稍后重试');
        const fallbackMsg: ChatMessage = {
          sessionId: sid!,
          role: 'assistant',
          content: '抱歉，处理您的请求时出现错误，请稍后重试。',
        };
        appendAssistantOrNotify(sid!, fallbackMsg);
      } else {
        try {
          const data = await sendMessageWithIdempotency(sid!, text, clientMessageId);
          const aiMsg: ChatMessage = {
            sessionId: sid!,
            role: 'assistant',
            content: data.response,
          };
          appendAssistantOrNotify(sid!, aiMsg);
          if (data.sessionTitle) {
            setSessions((prev) => prev.map((s) =>
              s.sessionId === sid ? { ...s, title: data.sessionTitle! } : s));
          }
        } catch (jsonErr: any) {
          toast.error('发送失败: ' + getErrorMessage(jsonErr));
          const fallbackMsg: ChatMessage = {
            sessionId: sid!,
            role: 'assistant',
            content: '抱歉，处理您的请求时出现错误，请稍后重试。',
          };
          appendAssistantOrNotify(sid!, fallbackMsg);
        }
      }
    } finally {
      sendingRef.current = false;
      setSending(false);
      clearStreamState(sid!);
      stopReveal();
      pendingStreamRef.current = '';
      streamAbortRef.current = null;
      stopRequestedRef.current = false;
      activeTurnRef.current = null;
    }
  };

  /** M6-36：停止当前思考/回复 → 显示“执行已中断”+ 重试按钮 */
  const handleStop = () => {
    const sid = currentSessionId;
    const turn = activeTurnRef.current;
    if (!sid || !sendingRef.current || !turn) return;
    stopRequestedRef.current = true;
    streamAbortRef.current?.abort();
    clearStreamState(sid);
    setInterruptedMap((prev) => ({
      ...prev,
      [sid]: { clientMessageId: turn.key, text: turn.text },
    }));
    // 后端：PENDING → INTERRUPTED + 中断标记（fire-and-forget，失败仅本地状态生效）
    chatApi.interruptTurn(sid, turn.key).catch(() => {});
  };

  /** M6-36：重试——同一幂等键续跑（后端有断点快照则跳过步骤 3~7） */
  const handleRetry = (sid: string) => {
    const turn = interruptedRef.current[sid];
    if (!turn) return;
    setInterruptedMap((prev) => {
      const next = { ...prev };
      delete next[sid];
      return next;
    });
    handleSend(sid, turn.text, turn.clientMessageId);
  };

  /** M4-9：显式结束会话（归档+收口摘要；不挂 beforeunload——刷新会误归档） */
  const handleCloseSession = async (sid: string) => {
    if (!confirm('确定结束该会话？结束后将不再出现在列表中（历史仍可读）。')) return;
    try {
      await chatApi.closeSession(sid);
      setDrafts((prev) => {
        const next = { ...prev };
        delete next[sid];
        return next;
      });
      toast.success('会话已结束');
      if (currentSessionId === sid) {
        historySidRef.current = null;
        setCurrentSessionId(null);
        setMessages([]);
      }
      loadSessions(true);
    } catch (err: any) {
      toast.error('结束会话失败: ' + getErrorMessage(err));
    }
  };

  // M5-1：双击编辑标题
  const startEdit = (s: ChatSession) => {
    cancelEditRef.current = false;
    setEditingSessionId(s.sessionId);
    setEditingTitle(s.title);
  };

  const cancelEdit = () => {
    cancelEditRef.current = true;
    setEditingSessionId(null);
    setEditingTitle('');
  };

  const saveTitle = async (sid: string) => {
    // Esc 取消后可能残留一次卸载 blur，消费并忽略
    if (cancelEditRef.current) {
      cancelEditRef.current = false;
      return;
    }
    if (titleSavingRef.current) return;
    const title = editingTitle.trim();
    if (!title) {
      toast.error('标题不能为空');
      return;
    }
    if (title.length > 200) {
      toast.error('标题不能超过200个字符');
      return;
    }
    titleSavingRef.current = true;
    setEditingSessionId(null);
    try {
      await chatApi.updateTitle(sid, title);
      setSessions((prev) => prev.map((s) =>
        s.sessionId === sid ? { ...s, title } : s));
      toast.success('标题已更新');
    } catch (err: any) {
      setEditingSessionId(sid);
      setEditingTitle(title);
      toast.error('标题更新失败: ' + getErrorMessage(err));
    } finally {
      titleSavingRef.current = false;
    }
  };

  // M5-1：消息复制（clipboard API + 降级）
  const copyMessage = async (content: string) => {
    try {
      await navigator.clipboard.writeText(content);
      toast.success('已复制');
    } catch {
      try {
        const ta = document.createElement('textarea');
        ta.value = content;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        toast.success('已复制');
      } catch {
        toast.error('复制失败');
      }
    }
  };

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-40" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  return (
    <div className="flex h-[calc(100vh-8rem)] gap-4">
      {/* 会话列表 */}
      <div className="w-64 flex-shrink-0 glass rounded-xl p-3 overflow-y-auto">
        <button
          onClick={handleNewSession}
          disabled={creatingSession}
          className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 mb-3 magnetic disabled:opacity-50"
        >
          <Plus className="h-4 w-4" /> 新会话
        </button>
        <div className="space-y-1">
          {sessions.map((s) => (
            <div key={s.sessionId} className="group relative">
              <div
                role="button"
                tabIndex={0}
                onClick={() => {
                  if (editingSessionId !== s.sessionId) {
                    setCurrentSessionId(s.sessionId);
                    // M6-48：点进会话 → 红点消失
                    setCompletedTurns((prev) => {
                      const next = { ...prev };
                      delete next[s.sessionId];
                      return next;
                    });
                  }
                }}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && editingSessionId !== s.sessionId) {
                    setCurrentSessionId(s.sessionId);
                  }
                }}
                className={cn(
                  'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left cursor-pointer transition-colors pr-8',
                  currentSessionId === s.sessionId
                    ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
                    : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                )}
              >
                {/* M6-48：思考/回复中 → 动态加载图标 */}
                {streamStates[s.sessionId]?.phase === 'thinking'
                  || streamStates[s.sessionId]?.phase === 'streaming' ? (
                  <Loader2 className="h-4 w-4 flex-shrink-0 animate-spin text-brand-500" />
                ) : (
                  <MessageSquare className="h-4 w-4 flex-shrink-0" />
                )}
                {editingSessionId === s.sessionId ? (
                  <input
                    ref={titleInputRef}
                    value={editingTitle}
                    onChange={(e) => setEditingTitle(e.target.value)}
                    onClick={(e) => e.stopPropagation()}
                    onDoubleClick={(e) => e.stopPropagation()}
                    onBlur={() => saveTitle(s.sessionId)}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') {
                        e.preventDefault();
                        saveTitle(s.sessionId);
                      } else if (e.key === 'Escape') {
                        cancelEdit();
                      }
                    }}
                    className="flex-1 min-w-0 rounded px-1 text-sm bg-white/70 dark:bg-slate-900/70 ring-1 ring-brand-500 outline-none"
                  />
                ) : (
                  <span
                    className="truncate flex-1"
                    title={s.title}
                    onDoubleClick={(e) => {
                      e.stopPropagation();
                      startEdit(s);
                    }}
                  >
                    {s.title}
                  </span>
                )}
              </div>
              {/* M4-9：显式结束会话（编辑态隐藏避免误触） */}
              {editingSessionId !== s.sessionId && (
                <button
                  title="结束会话"
                  onClick={() => handleCloseSession(s.sessionId)}
                  className="absolute right-1.5 top-1/2 -translate-y-1/2 p-1 rounded-md opacity-0 group-hover:opacity-100 hover:bg-red-50 dark:hover:bg-red-900/20 text-slate-400 hover:text-red-500 transition-all"
                >
                  <Archive className="h-3.5 w-3.5" />
                </button>
              )}
              {/* M6-48：后台会话完成回复 → 红点提示（点进会话后清除） */}
              {completedTurns[s.sessionId] && currentSessionId !== s.sessionId && (
                <span
                  title="有新回复"
                  className="absolute right-7 top-1/2 -translate-y-1/2 h-2 w-2 rounded-full bg-red-500"
                />
              )}
            </div>
          ))}
          {sessions.length === 0 && (
            <p className="text-xs text-slate-400 text-center py-4">暂无会话</p>
          )}
        </div>
      </div>

      {/* 消息区 */}
      <div className="flex-1 flex flex-col glass rounded-xl overflow-hidden">
        <div className="relative flex-1 overflow-hidden">
          <div
            ref={scrollRef}
            onScroll={updateNearBottom}
            className="h-full overflow-y-auto p-4 space-y-4"
          >
            {messages.length === 0 ? (
              <div className="flex flex-col items-center justify-center h-full text-slate-400">
                <MessageSquare className="h-12 w-12 mb-3 opacity-50" />
                <p>开始一段新的旅游规划对话</p>
              </div>
            ) : (
              messages.map((msg, idx) => (
                <div
                  key={idx}
                  className={cn('group relative flex', msg.role === 'user' ? 'justify-end' : 'justify-start')}
                >
                  <div
                    className={cn(
                      'flex max-w-[70%] flex-col',
                      msg.role === 'user' ? 'items-end' : 'items-start'
                    )}
                  >
                    <div
                      className={cn(
                        'relative rounded-2xl px-4 py-2.5',
                        msg.role === 'user'
                          ? 'bg-brand-500 text-white rounded-br-sm'
                          : 'bg-slate-100 dark:bg-slate-800 rounded-bl-sm'
                      )}
                    >
                      {msg.role === 'user' ? (
                        <p className="text-sm whitespace-pre-wrap">{msg.content}</p>
                      ) : (
                        <div className="text-sm">
                          <ChatMessageContent content={msg.content} />
                        </div>
                      )}
                    </div>
                    {/* M6-49：时间戳与复制按钮在气泡外（左下角），复制按钮透明背景 */}
                    <div
                      className={cn(
                        'mt-1 flex items-center gap-1 opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity',
                        msg.role === 'user' ? 'justify-end' : 'justify-start'
                      )}
                    >
                      {/* M6-50：时间戳在左、复制按钮在右 */}
                      <span className="text-[10px] text-slate-400/70">
                        {formatMessageTime(msg.createdAt)}
                      </span>
                      <button
                        type="button"
                        onClick={() => copyMessage(msg.content)}
                        className="rounded-md bg-transparent p-1 text-slate-400 hover:text-brand-500"
                        title="复制"
                        aria-label="复制消息"
                      >
                        <Copy className="h-3 w-3" />
                      </button>
                    </div>
                  </div>
                </div>
              ))
            )}
            {/* M6：思考气泡（spinner + 浅灰半透明阶段提示） */}
            {currentStreamState?.phase === 'thinking' && (
              <div className="flex justify-start">
                <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100/70 dark:bg-slate-800/70">
                  <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Agent 思考中…
                  </div>
                  {currentStreamState.thinkingLines.length > 0 && (
                    <div className="mt-2 space-y-1">
                      {currentStreamState.thinkingLines.map((line, idx) => (
                        <p key={idx} className="text-xs text-slate-500/80 dark:text-slate-400/80">
                          {line}
                        </p>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}
            {/* M6：流式输出（思考完成后替换思考气泡） */}
            {currentStreamState?.phase === 'streaming' && (
              <div className="flex justify-start">
                <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100 dark:bg-slate-800">
                  <p className="text-sm whitespace-pre-wrap">{currentStreamState.streamingText}</p>
                </div>
              </div>
            )}
            {/* M6-36：执行已中断 + 重试（每会话最多一个断点） */}
            {interruptedTurns[currentSessionId ?? ''] && (
              <div className="flex justify-start">
                <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100 dark:bg-slate-800">
                  <p className="text-sm text-slate-600 dark:text-slate-300">执行已中断</p>
                  <button
                    type="button"
                    onClick={() => handleRetry(currentSessionId!)}
                    className="mt-1.5 inline-flex items-center gap-1 text-xs text-brand-500 hover:text-brand-600"
                  >
                    <RotateCcw className="h-3 w-3" /> 重试
                  </button>
                </div>
              </div>
            )}
            <div ref={messagesEndRef} />
          </div>
          {/* M5-1：非底部时显示“回到底部”，点击立刻直达 */}
          {!isNearBottom && (
            <button
              type="button"
              onClick={() => {
                const el = scrollRef.current;
                if (el) {
                  el.scrollTo({ top: el.scrollHeight });
                  updateNearBottom();
                }
              }}
              className="absolute bottom-3 left-1/2 -translate-x-1/2 z-10 flex items-center gap-1 px-3 py-1.5 rounded-full bg-brand-500 text-white text-xs shadow-lg hover:bg-brand-600"
            >
              <ArrowDown className="h-3.5 w-3.5" /> 回到底部
            </button>
          )}
        </div>

        {/* 输入区 */}
        <div className="border-t border-slate-200 dark:border-slate-800 p-3 flex gap-2">
          <input
            value={input}
            onChange={(e) =>
              setDrafts((prev) => ({ ...prev, [activeDraftKey]: e.target.value }))
            }
            onKeyDown={(e) => e.key === 'Enter' && handleSend()}
            placeholder="输入消息..."
            className="flex-1 px-4 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
          />
          {/* M6-49：思考阶段可停止；流式回复阶段显示不可点击的发送按钮 */}
          {sending && activeTurnRef.current?.sid === currentSessionId
            && currentStreamState?.phase === 'thinking' ? (
            <button
              type="button"
              onClick={handleStop}
              title="停止"
              aria-label="停止"
              className="px-4 py-2 rounded-lg bg-red-500 text-white hover:bg-red-600 magnetic"
            >
              <Square className="h-4 w-4" />
            </button>
          ) : (
            <button
              type="button"
              onClick={() => handleSend()}
              disabled={!input.trim() || sending || creatingSession}
              className="px-4 py-2 rounded-lg bg-brand-500 text-white hover:bg-brand-600 disabled:opacity-50 magnetic"
            >
              <Send className="h-4 w-4" />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export default function ChatPage() {
  return <ChatContent />;
}
