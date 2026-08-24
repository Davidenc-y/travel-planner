'use client';

import { useEffect, useState, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, Send, Plus, MessageSquare, Archive, Copy, ArrowDown } from 'lucide-react';
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
  // M6：流式状态（思考气泡 / 流式文本）
  const [streamPhase, setStreamPhase] = useState<'idle' | 'thinking' | 'streaming'>('idle');
  const [thinkingLines, setThinkingLines] = useState<string[]>([]);
  const [streamingText, setStreamingText] = useState('');
  const streamAbortRef = useRef<AbortController | null>(null);
  const prevSessionRef = useRef<string | null>(currentSessionId);
  // M6-5：待展示文本队列与揭示定时器
  const pendingStreamRef = useRef('');
  const revealTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const activeDraftKey = currentSessionId ?? '__new__';
  const input = drafts[activeDraftKey] ?? '';

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

  // M6：切换会话时取消在途流并复位流式 UI
  useEffect(() => {
    if (prevSessionRef.current !== currentSessionId) {
      prevSessionRef.current = currentSessionId;
      streamAbortRef.current?.abort();
      streamAbortRef.current = null;
      stopReveal();
      pendingStreamRef.current = '';
      setStreamPhase('idle');
      setThinkingLines([]);
      setStreamingText('');
    }
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
      setStreamingText((prev) => prev + rest);
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
      setStreamingText((prev) => prev + chunk);
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
      // M5-1：不再传固定标题，标题由后端首条消息联动生成
      const res = await chatApi.createSession(userId!);
      const sid = res.data.data;
      setCurrentSessionId(sid);
      setMessages([]);
      // M5-1：使可能仍在途的旧会话历史响应失效，避免覆盖新会话空消息区
      historySidRef.current = null;
      instantScrollRef.current = true;
      await loadSessions(true);
      toast.success('新会话已创建');
    } catch {
      toast.error('创建会话失败');
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
            setStreamPhase('thinking');
            const msg = p.message;
            if (msg) {
              setThinkingLines((prev) => (prev.includes(msg) ? prev : [...prev, msg]));
            }
          },
          onToken: (p) => {
            if (p.text) {
              acc += p.text;
              pendingStreamRef.current += p.text;
              setStreamPhase('streaming');
              startReveal();
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

  const handleSend = async () => {
    const text = input.trim();
    if (!text || sendingRef.current || creatingRef.current) return;
    const hadNoSession = !currentSessionId;

    // M5-1：初始界面直接发送 → 自动创建会话并进入
    let sid = currentSessionId;
    if (!sid) {
      creatingRef.current = true;
      setCreatingSession(true);
      try {
        const res = await chatApi.createSession(userId!);
        sid = res.data.data;
        setCurrentSessionId(sid);
        setMessages([]);
        historySidRef.current = null;
        instantScrollRef.current = true;
        await loadSessions(true);
      } catch (err: any) {
        toast.error('创建会话失败: ' + getErrorMessage(err));
        return;
      } finally {
        creatingRef.current = false;
        setCreatingSession(false);
      }
    }

    const userMsg: ChatMessage = {
      sessionId: sid!,
      role: 'user',
      content: text,
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
    sendingRef.current = true;
    setSending(true);
    setStreamPhase('thinking');
    setThinkingLines(['已收到，Agent 正在思考…']);
    setStreamingText('');

    // M4-9：消息幂等键——超时/40904 重试携带同键，杜绝重复追加/双跑
    const clientMessageId = crypto.randomUUID();
    try {
      // M6：优先 SSE 流式；失败自动回退 JSON 端点
      const streamed = await sendStreamWithRetry(sid!, text, clientMessageId);
      const aiMsg: ChatMessage = { sessionId: sid!, role: 'assistant', content: streamed.text };
      setMessages((prev) => [...prev, aiMsg]);
      if (streamed.sessionTitle) {
        setSessions((prev) => prev.map((s) =>
          s.sessionId === sid ? { ...s, title: streamed.sessionTitle! } : s));
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return; // 主动取消（切换会话/卸载）
      const code = err?.response?.data?.code ?? err?.code;
      if (code === 40904) {
        toast.error('发送超时，请稍后重试');
        setMessages((prev) => [...prev, {
          sessionId: sid!,
          role: 'assistant',
          content: '抱歉，处理您的请求时出现错误，请稍后重试。',
        }]);
      } else {
        try {
          const data = await sendMessageWithIdempotency(sid!, text, clientMessageId);
          setMessages((prev) => [...prev, {
            sessionId: sid!,
            role: 'assistant',
            content: data.response,
          }]);
          if (data.sessionTitle) {
            setSessions((prev) => prev.map((s) =>
              s.sessionId === sid ? { ...s, title: data.sessionTitle! } : s));
          }
        } catch (jsonErr: any) {
          toast.error('发送失败: ' + getErrorMessage(jsonErr));
          setMessages((prev) => [...prev, {
            sessionId: sid!,
            role: 'assistant',
            content: '抱歉，处理您的请求时出现错误，请稍后重试。',
          }]);
        }
      }
    } finally {
      sendingRef.current = false;
      setSending(false);
      setStreamPhase('idle');
      setThinkingLines([]);
      setStreamingText('');
      stopReveal();
      pendingStreamRef.current = '';
      streamAbortRef.current = null;
    }
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
                  if (editingSessionId !== s.sessionId) setCurrentSessionId(s.sessionId);
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
                <MessageSquare className="h-4 w-4 flex-shrink-0" />
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
                      'relative max-w-[70%] rounded-2xl px-4 py-2.5',
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
                    {/* M5-1：复制按钮——常态隐藏，hover 该消息附近时显示 */}
                    <button
                      type="button"
                      onClick={() => copyMessage(msg.content)}
                      className="absolute -bottom-2 right-1 p-1 rounded-md opacity-0 group-hover:opacity-100 focus-visible:opacity-100 transition-opacity bg-slate-200 dark:bg-slate-700 text-slate-500 hover:text-brand-500"
                      title="复制"
                      aria-label="复制消息"
                    >
                      <Copy className="h-3 w-3" />
                    </button>
                  </div>
                </div>
              ))
            )}
            {/* M6：思考气泡（spinner + 浅灰半透明阶段提示） */}
            {streamPhase === 'thinking' && (
              <div className="flex justify-start">
                <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100/70 dark:bg-slate-800/70">
                  <div className="flex items-center gap-2 text-sm text-slate-500 dark:text-slate-400">
                    <Loader2 className="h-4 w-4 animate-spin" />
                    Agent 思考中…
                  </div>
                  {thinkingLines.length > 0 && (
                    <div className="mt-2 space-y-1">
                      {thinkingLines.map((line, idx) => (
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
            {streamPhase === 'streaming' && (
              <div className="flex justify-start">
                <div className="max-w-[70%] rounded-2xl rounded-bl-sm px-4 py-2.5 bg-slate-100 dark:bg-slate-800">
                  <p className="text-sm whitespace-pre-wrap">{streamingText}</p>
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
              className="absolute bottom-3 left-1/2 -translate-x-1/2 z-10 flex items-center gap-1 px-3 py-1.5 rounded-full bg-brand-500 text-white text-xs shadow-lg hover:bg-brand-600 magnetic"
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
          <button
            onClick={handleSend}
            disabled={!input.trim() || sending || creatingSession}
            className="px-4 py-2 rounded-lg bg-brand-500 text-white hover:bg-brand-600 disabled:opacity-50 magnetic"
          >
            <Send className="h-4 w-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

export default function ChatPage() {
  return <ChatContent />;
}
