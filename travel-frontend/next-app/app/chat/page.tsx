'use client';

import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { ArrowDown, MessageSquare, Send, Square } from 'lucide-react';
import { chatApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ChatMessage, ChatResponse } from '@/types';
import { Skeleton } from '@/components/ui/skeleton';
import { SessionList } from '@/components/chat/SessionList';
import {
  InterruptedBubble,
  MessageBubble,
  StreamingBubble,
  ThinkingBubble,
} from '@/components/chat/MessageBubble';
import { useChatStream } from '@/hooks/useChatStream';
import { useSessionList } from '@/hooks/useSessionList';
import { useModelPreference } from '@/hooks/useModelPreference';
import { ModelSelector } from '@/components/model/ModelSelector';

interface InterruptedTurn {
  clientMessageId: string;
  text: string;
}

/**
 * M6-58/T10：聊天页布局编排。
 *
 * <p>会话列表/消息气泡纯展示组件在 components/chat（SessionList、MessageBubble）；
 * SSE 流式与 reveal 队列在 hooks/useChatStream；会话 CRUD/置顶/标题在
 * hooks/useSessionList。本文件仅保留跨域状态装配（消息、草稿、当前会话、
 * 中断轮次、发送编排）与 M5-1/M6-47/M6-48 竞态防护。</p>
 */
function ChatContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  // M5-1：草稿按会话隔离（未选中会话时使用 '__new__'）
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [sending, setSending] = useState(false);
  const [creatingSession, setCreatingSession] = useState(false);
  // M5-1：滚动状态（回到底部按钮）
  const [isNearBottom, setIsNearBottom] = useState(true);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const instantScrollRef = useRef(true);
  const nearBottomRef = useRef(true);
  const creatingRef = useRef(false);
  const sendingRef = useRef(false);
  // M5-1：历史请求竞态防护——切会话/新建会话后，过期历史响应不得覆盖当前消息
  const historySidRef = useRef<string | null>(null);
  const currentSessionRef = useRef<string | null>(null);
  // M6-59：新建会话乐观消息保护——getHistory 空响应不得覆盖已显示的用户消息
  const freshSessionRef = useRef<string | null>(null);
  // M6-48：后台会话完成回复 → 会话列表右侧红点提示（点进会话后清除）
  const [completedTurns, setCompletedTurns] = useState<Record<string, boolean>>({});
  // M6-36：每会话最多一个中断轮次（重试/新消息时清除）
  const [interruptedTurns, setInterruptedTurns] = useState<Record<string, InterruptedTurn>>({});
  const interruptedRef = useRef<Record<string, InterruptedTurn>>({});
  const activeTurnRef = useRef<{ sid: string; key: string; text: string } | null>(null);
  const stopRequestedRef = useRef(false);

  const sessionList = useSessionList(userId ?? null);
  const chatStream = useChatStream(() => currentSessionRef.current);
  // M7 Batch 3：用户模型偏好（'' = 智能默认）
  const modelPref = useModelPreference();

  const activeDraftKey = currentSessionId ?? '__new__';
  const input = drafts[activeDraftKey] ?? '';
  const currentStreamState = currentSessionId
    ? chatStream.streamStates[currentSessionId] : undefined;

  const setInterruptedMap = (
    updater: (prev: Record<string, InterruptedTurn>) => Record<string, InterruptedTurn>,
  ) => {
    setInterruptedTurns((prev) => {
      const next = updater(prev);
      interruptedRef.current = next;
      return next;
    });
  };

  // M6-49：回复完成（成功/兜底）→ 当前会话直接追加，否则红点提示；并置顶会话
  const appendAssistantOrNotify = (sid: string, msg: ChatMessage) => {
    if (currentSessionRef.current === sid) {
      setMessages((prev) => [...prev, msg]);
    } else {
      setCompletedTurns((prev) => ({ ...prev, [sid]: true }));
    }
    sessionList.finalizeTurnSession(sid);
  };

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    if (userId) sessionList.loadSessions();
  }, [userId, isAuthenticated]);

  useEffect(() => {
    // M6-59：离开新建会话后其乐观消息保护失效（late 响应由 currentSessionRef 归属校验兜底）
    if (currentSessionId && freshSessionRef.current
      && freshSessionRef.current !== currentSessionId) {
      freshSessionRef.current = null;
    }
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

  // M6-48：切换会话不再 abort 在途流（后端继续思考）——仅停止当前会话逐字揭示
  useEffect(() => {
    chatStream.stopRevealForSwitch();
  }, [currentSessionId]);

  // M6：组件卸载时取消在途流
  useEffect(() => () => {
    chatStream.dispose();
  }, []);

  const updateNearBottom = () => {
    const el = scrollRef.current;
    if (!el) return;
    const near = el.scrollHeight - el.scrollTop - el.clientHeight <= 40;
    nearBottomRef.current = near;
    setIsNearBottom(near);
  };

  const loadHistory = async (sid: string) => {
    historySidRef.current = sid;
    try {
      const res = await chatApi.getHistory(sid);
      if (historySidRef.current !== sid || currentSessionRef.current !== sid) return;
      // M6-59：新建会话已乐观显示用户消息——消费标记并跳过覆盖（响应被本次消费）
      if (freshSessionRef.current === sid) {
        freshSessionRef.current = null;
        return;
      }
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
      // M6-59：新建会话历史加载失败也不得清空已乐观显示的消息
      if (freshSessionRef.current === sid) {
        freshSessionRef.current = null;
        return;
      }
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
  const sendMessageWithIdempotency = async (
    sid: string,
    text: string,
    key: string,
    model?: string,
  ): Promise<ChatResponse> => {
    const maxAttempts = 4;
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      try {
        const res = await chatApi.sendMessage(sid, text, key, model);
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
        sid = await sessionList.createSession(userId!);
        if (!sid) return;
        // M6-59：标记新建会话——本次发送的乐观消息优先，历史空响应不得覆盖
        freshSessionRef.current = sid;
        // M6-50/M6-60：标记为"首条消息中创建"——完成后再刷新权威列表（标题后端联动）；
        // 会话已由 createSession 立即加入左侧列表，思考期间可切走再点回
        setCurrentSessionId(sid);
        setMessages([]);
        historySidRef.current = null;
        instantScrollRef.current = true;
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
    chatStream.setStreamState(sid!, (s) => ({
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
      const streamed = await chatStream.sendStreamWithRetry(
        sid!, text, clientMessageId, modelPref.model);
      const aiMsg: ChatMessage = {
        sessionId: sid!,
        role: 'assistant',
        content: streamed.text,
        createdAt: new Date().toISOString(),
      };
      appendAssistantOrNotify(sid!, aiMsg);
      if (streamed.sessionTitle) {
        sessionList.updateSessionTitle(sid!, streamed.sessionTitle);
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return; // 主动取消（切换会话/卸载）
      const code = err?.response?.data?.code ?? err?.code;
      // M7 Batch 3：所选模型不可用 → 提示并回退智能默认（不重复尝试同模型）
      if (code === 40005) {
        toast.error('所选模型不可用，已切换回智能默认');
        modelPref.select('');
        return;
      }
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
          const data = await sendMessageWithIdempotency(
            sid!, text, clientMessageId, modelPref.model);
          const aiMsg: ChatMessage = {
            sessionId: sid!,
            role: 'assistant',
            content: data.response,
          };
          appendAssistantOrNotify(sid!, aiMsg);
          if (data.sessionTitle) {
            sessionList.updateSessionTitle(sid!, data.sessionTitle);
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
      chatStream.clearStream(sid!);
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
    chatStream.abortStream();
    chatStream.clearStreamState(sid);
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
    const closed = await sessionList.closeSession(sid);
    if (!closed) return;
    setDrafts((prev) => {
      const next = { ...prev };
      delete next[sid];
      return next;
    });
    if (currentSessionId === sid) {
      historySidRef.current = null;
      setCurrentSessionId(null);
      setMessages([]);
    }
  };

  // M6-48：点进会话 → 红点消失
  const handleSelectSession = (sid: string) => {
    setCurrentSessionId(sid);
    setCompletedTurns((prev) => {
      const next = { ...prev };
      delete next[sid];
      return next;
    });
  };

  if (sessionList.loading) {
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
      <SessionList
        sessions={sessionList.sessions}
        currentSessionId={currentSessionId}
        creatingSession={creatingSession}
        streamStates={chatStream.streamStates}
        completedTurns={completedTurns}
        editingSessionId={sessionList.editingSessionId}
        editingTitle={sessionList.editingTitle}
        onNewSession={handleNewSession}
        onSelect={handleSelectSession}
        onEnterSelect={(sid) => setCurrentSessionId(sid)}
        onStartEdit={sessionList.startEdit}
        onTitleChange={sessionList.changeEditingTitle}
        onSaveTitle={sessionList.saveTitle}
        onCancelEdit={sessionList.cancelEdit}
        onCloseSession={handleCloseSession}
      />

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
                <MessageBubble key={idx} message={msg} />
              ))
            )}
            {/* M6：思考气泡（spinner + 浅灰半透明阶段提示） */}
            {currentStreamState?.phase === 'thinking' && (
              <ThinkingBubble lines={currentStreamState.thinkingLines} />
            )}
            {/* M6：流式输出（思考完成后替换思考气泡） */}
            {currentStreamState?.phase === 'streaming' && (
              <StreamingBubble text={currentStreamState.streamingText} />
            )}
            {/* M6-36：执行已中断 + 重试（每会话最多一个断点） */}
            {interruptedTurns[currentSessionId ?? ''] && (
              <InterruptedBubble onRetry={() => handleRetry(currentSessionId!)} />
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
        <div className="border-t border-slate-200 dark:border-slate-800 p-3 flex gap-2 items-center">
          {/* M7 Batch 3：模型选择（智能默认=不传 model） */}
          <div className="w-36 flex-shrink-0">
            {/* M7-7：输入区贴底 → 模型下拉向上展开，避免溢出视口无法选择 */}
            <ModelSelector value={modelPref.model} onChange={modelPref.select} dropUp />
          </div>
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
