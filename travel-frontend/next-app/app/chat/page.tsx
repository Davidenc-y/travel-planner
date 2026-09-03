'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { ArrowDown, MessagesSquare } from 'lucide-react';
import { chatApi, getErrorMessage, httpErrorCode, isAbortError } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ChatMessage, ChatResponse } from '@/types';
import { generateUUID } from '@/lib/utils';
import { ERROR_CODE } from '@/lib/constants';
import { Skeleton } from '@/components/ui/skeleton';
import { Dialog } from '@/components/ui/dialog';
import { SessionList } from '@/components/chat/SessionList';
import { TurnScrollbar } from '@/components/chat/TurnScrollbar';
import { Composer } from '@/components/chat/Composer';
import { ChatHeader } from '@/components/chat/ChatHeader';
import {
  InterruptedBubble,
  MessageBubble,
  StreamingBubble,
  ThinkingTimeline,
} from '@/components/chat/MessageBubble';
import { useChatStream } from '@/hooks/useChatStream';
import { useSessionList } from '@/hooks/useSessionList';
import { useModelPreference } from '@/hooks/useModelPreference';
import { ModelSelector } from '@/components/model/ModelSelector';
import { SUGGESTED_PROMPTS } from '@/lib/suggested-prompts';

interface InterruptedTurn {
  clientMessageId: string;
  text: string;
}

// B3/09 C-12：日期分隔（本地日期粒度）
function sameDay(a?: string, b?: string): boolean {
  if (!a || !b) return false;
  const da = new Date(a);
  const db = new Date(b);
  if (Number.isNaN(da.getTime()) || Number.isNaN(db.getTime())) return false;
  return da.getFullYear() === db.getFullYear()
    && da.getMonth() === db.getMonth()
    && da.getDate() === db.getDate();
}

function DateSeparator({ iso }: { iso: string }) {
  const d = new Date(iso);
  const now = new Date();
  const sameDayNow = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  const label = sameDayNow
    ? '今天'
    : `${d.getMonth() + 1} 月 ${d.getDate()} 日`;
  return (
    <div className="flex items-center gap-3 py-1" aria-hidden>
      <span className="h-px flex-1 bg-line" />
      <span className="text-[10px] text-ink-faint">{label}</span>
      <span className="h-px flex-1 bg-line" />
    </div>
  );
}

/**
 * M6-58/T10 + B3（09）：聊天页布局编排。
 *
 * <p>会话列表/消息气泡纯展示组件在 components/chat（SessionList、MessageBubble）；
 * SSE 流式与 reveal 队列在 hooks/useChatStream；会话 CRUD/置顶/标题在
 * hooks/useSessionList。本文件仅保留跨域状态装配（消息、草稿、当前会话、
 * 中断轮次、发送编排）与 M5-1/M6-47/M6-48 竞态防护。</p>
 *
 * <p>09 增量：C-01 多行 Composer（Enter 发送/Shift+Enter 换行/Esc 清草稿/自动增高）、
 * C-02 流式 Markdown（守卫在 StreamingBubble）、C-03 执行过程块（thinkingRef 采集）、
 * C-04 重新生成/编辑重发（asNew 语义：新 key 新轮次，历史不删改）、C-05 侧栏搜索分组
 * 与会话头部/窄屏抽屉、C-06 空态推荐词、C-07 tokens/耗时、C-11 键盘、C-12 日期分隔。
 * 停止按钮仍仅 thinking 阶段（M6-49 现状，C-08/D-18 待后端确认）。</p>
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
  const [drawerOpen, setDrawerOpen] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
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
  // B3/09 C-03/C-07：本轮执行耗时起点（success 时与 thinkingRef 一起生成 process）
  const turnStartRef = useRef<number>(0);
  // B3/09 C-04：消息镜像（regenerate 取最后一条 user 文本用，避免闭包过期）
  const messagesRef = useRef<ChatMessage[]>([]);
  useEffect(() => {
    messagesRef.current = messages;
  }, [messages]);

  const sessionList = useSessionList(userId ?? null);
  const chatStream = useChatStream(() => currentSessionRef.current);
  // M7 Batch 3：用户模型偏好（'' = 智能默认）
  const modelPref = useModelPreference();

  const activeDraftKey = currentSessionId ?? '__new__';
  const input = drafts[activeDraftKey] ?? '';
  const currentStreamState = currentSessionId
    ? chatStream.streamStates[currentSessionId] : undefined;
  const currentSession = sessionList.sessions.find((s) => s.sessionId === currentSessionId);

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

  // B3/PE-05（F-26）：onScroll 经 rAF 节流，避免高频 setState
  const scrollRafRef = useRef<number | null>(null);
  const updateNearBottom = () => {
    if (scrollRafRef.current !== null) return;
    scrollRafRef.current = requestAnimationFrame(() => {
      scrollRafRef.current = null;
      const el = scrollRef.current;
      if (!el) return;
      const near = el.scrollHeight - el.scrollTop - el.clientHeight <= 40;
      nearBottomRef.current = near;
      setIsNearBottom(near);
    });
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
      } catch (err: unknown) {
        const code = httpErrorCode(err);
        if (code === 40904 && attempt < maxAttempts - 1) {
          await new Promise((r) => setTimeout(r, 3000));
          continue;
        }
        throw err;
      }
    }
    throw new Error('发送超时，请稍后重试');
  };

  /**
   * 发送编排（M4-9/M5-1/M6 系列语义保留）。
   * B3/09 C-04：新增 asNew 模式——retrySid + asNew=true 表示"以既有会话发起一轮全新发送"
   * （重新生成/编辑重发）：乐观追加 user 消息 + 清旧断点 + 全新 clientMessageId（新轮次，
   * 非断点续跑，历史不删改）。retryKey 传入时也不作幂等续跑使用（asNew 固定生成新键）。
   */
  const handleSend = async (
    retrySid?: string,
    retryText?: string,
    retryKey?: string,
    asNew = false,
  ) => {
    const isRetry = retrySid != null && !asNew;
    const text = retrySid != null ? retryText! : input.trim();
    if (!text || sendingRef.current || creatingRef.current) return;
    const hadNoSession = !isRetry && !currentSessionId;

    // M5-1：初始界面直接发送 → 自动创建会话并进入
    let sid = retrySid != null ? retrySid : currentSessionId;
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
        localKey: `u-${crypto.randomUUID()}`,
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
    turnStartRef.current = Date.now();

    // M4-9：消息幂等键——超时/40904 重试携带同键，杜绝重复追加/双跑
    const clientMessageId = isRetry ? retryKey! : crypto.randomUUID();
    activeTurnRef.current = { sid: sid!, key: clientMessageId, text };
    try {
      // M6：优先 SSE 流式；失败自动回退 JSON 端点
      const streamed = await chatStream.sendStreamWithRetry(
        sid!, text, clientMessageId, modelPref.model);
      const stages = chatStream.getThinkingLines(sid!);
      const aiMsg: ChatMessage = {
        sessionId: sid!,
        role: 'assistant',
        content: streamed.text,
        createdAt: new Date().toISOString(),
        localKey: `a-${clientMessageId}`,
        tokens: streamed.tokens,
        process: {
          stages: stages.length > 0
            ? stages
            : ['已收到，Agent 正在思考…'],
          elapsedMs: Date.now() - turnStartRef.current,
        },
      };
      appendAssistantOrNotify(sid!, aiMsg);
      if (streamed.sessionTitle) {
        sessionList.updateSessionTitle(sid!, streamed.sessionTitle);
      }
    } catch (err: unknown) {
      if (isAbortError(err)) return; // 主动取消（切换会话/卸载）
      const code = httpErrorCode(err);
      // M7 Batch 3：所选模型不可用 → 提示并回退智能默认（不重复尝试同模型）
      if (code === ERROR_CODE.MODEL_NOT_FOUND) {
        toast.error('所选模型不可用，已切换回智能默认');
        modelPref.select('');
        return;
      }
      // M8-9h：模型额度不足——不重试、不回退 JSON，直接给出明确提示
      if (code === ERROR_CODE.MODEL_QUOTA_EXCEEDED) {
        const quotaText = getErrorMessage(err);
        toast.error(quotaText);
        const quotaMsg: ChatMessage = {
          sessionId: sid!,
          role: 'assistant',
          content: `⚠️ ${quotaText}`,
          localKey: `a-${crypto.randomUUID()}`,
        };
        appendAssistantOrNotify(sid!, quotaMsg);
        return;
      }
      if (code === ERROR_CODE.MESSAGE_PROCESSING) {
        toast.error('发送超时，请稍后重试');
        const fallbackMsg: ChatMessage = {
          sessionId: sid!,
          role: 'assistant',
          content: '抱歉，处理您的请求时出现错误，请稍后重试。',
          localKey: `a-${crypto.randomUUID()}`,
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
            tokens: data.tokens,
            itineraryId: data.itineraryId,
            localKey: `a-${clientMessageId}`,
          };
          appendAssistantOrNotify(sid!, aiMsg);
          if (data.sessionTitle) {
            sessionList.updateSessionTitle(sid!, data.sessionTitle);
          }
        } catch (jsonErr: unknown) {
          toast.error('发送失败: ' + getErrorMessage(jsonErr));
          const fallbackMsg: ChatMessage = {
            sessionId: sid!,
            role: 'assistant',
            content: '抱歉，处理您的请求时出现错误，请稍后重试。',
            localKey: `a-${crypto.randomUUID()}`,
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

  /** M6-36：停止当前思考/回复 → 显示“执行已中断”+ 重试按钮（C-08/D-18：流式阶段待后端确认后扩展） */
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

  /** B3/09 C-04：重新生成——对最后一条 assistant 回复，以同文本发起新轮次（新 key，历史不删改）。
   *  经 handleSendRef 调用最新 handleSend，避免 useCallback 捕获过期 modelPref（R11）。 */
  const handleSendRef = useRef(handleSend);
  useEffect(() => {
    handleSendRef.current = handleSend;
  });

  const handleRegenerate = useCallback((sid: string) => {
    const list = messagesRef.current;
    const lastUser = [...list].reverse().find((m) => m.role === 'user');
    if (!lastUser) return;
    handleSendRef.current(sid, lastUser.content, undefined, true);
  }, []);

  /** B3/09 C-04：编辑并重发——user 消息编辑后以新文本发起新轮次（原消息与历史不删改） */
  const handleEditResend = useCallback((text: string) => {
    const sid = currentSessionRef.current;
    if (!sid) return;
    handleSendRef.current(sid, text, undefined, true);
  }, []);

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
    setDrawerOpen(false);
  };

  // B3/09 C-01：textarea 自动增高（1~8 行）
  useEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 192)}px`;
  }, [input]);

  // B3/09 C-06：推荐提示词——填充草稿不发送
  const applySuggestion = (prompt: string) => {
    setDrafts((prev) => ({ ...prev, [activeDraftKey]: prompt }));
    textareaRef.current?.focus();
  };

  if (sessionList.loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-10 w-40" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  const streaming = currentStreamState?.phase === 'thinking'
    || currentStreamState?.phase === 'streaming';

  return (
    <div className="flex h-[calc(100vh-8rem)] gap-4">
      {/* 会话列表（窄屏收进抽屉，C-05） */}
      <div className="hidden md:flex flex-shrink-0">
        <SessionList
          sessions={sessionList.sessions}
          currentSessionId={currentSessionId}
          creatingSession={creatingSession}
          streamStates={chatStream.streamStates}
          completedTurns={completedTurns}
          editingSessionId={sessionList.editingSessionId}
          editingTitle={sessionList.editingTitle}
          pinnedIds={sessionList.pinnedIds}
          onTogglePin={sessionList.togglePin}
          onNewSession={handleNewSession}
          onSelect={handleSelectSession}
          onEnterSelect={(sid) => setCurrentSessionId(sid)}
          onStartEdit={sessionList.startEdit}
          onTitleChange={sessionList.changeEditingTitle}
          onSaveTitle={sessionList.saveTitle}
          onCancelEdit={sessionList.cancelEdit}
          onCloseSession={handleCloseSession}
        />
      </div>

      {/* 消息区（C1 参考稿对齐：去卡片化，平铺在页面背景上） */}
      <div className="flex-1 flex flex-col overflow-hidden">
        {/* R2/C-2：会话头部纯展示组件 */}
        <ChatHeader
          title={currentSession?.title ?? '新会话'}
          streaming={streaming}
          onOpenDrawer={() => setDrawerOpen(true)}
        />

        <div className="relative flex-1 overflow-hidden">
          <div
            ref={scrollRef}
            onScroll={updateNearBottom}
            className="relative h-full overflow-y-auto py-4 pl-9 pr-4 space-y-5"
          >
            {messages.length === 0 && !currentStreamState ? (
              /* B3/09 C-06：空态——能力说明 + 推荐提示词（点击填充草稿，不自动发送） */
              <div className="flex flex-col items-center justify-center h-full px-4 text-ink-faint">
                <MessagesSquare className="h-12 w-12 mb-3 opacity-50" />
                <p className="text-sm">开始一段新的旅游规划对话</p>
                <p className="mt-1 text-xs">我可以规划行程、检索景点，并记住你的旅行偏好</p>
                <div className="mt-6 grid w-full max-w-md grid-cols-1 sm:grid-cols-2 gap-2">
                  {SUGGESTED_PROMPTS.map((prompt) => (
                    <button
                      key={prompt}
                      type="button"
                      onClick={() => applySuggestion(prompt)}
                      className="rounded-xl border border-line bg-surface px-3 py-2 text-left text-xs text-ink-secondary transition-colors hover:border-brand-400 hover:text-ink focus-ring"
                    >
                      {prompt}
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              messages.map((msg, idx) => {
                const showSeparator = idx === 0 || !sameDay(messages[idx - 1].createdAt, msg.createdAt);
                const isLastAssistant = !isLastMessageUser(messages)
                  && idx === messages.length - 1 && msg.role === 'assistant';
                const key = msg.id ?? msg.localKey ?? `i-${idx}`;
                return (
                  <div
                    key={key}
                    data-user-turn={msg.role === 'user' ? key : undefined}
                    className="space-y-4"
                  >
                    {showSeparator && msg.createdAt && <DateSeparator iso={msg.createdAt} />}
                    <MessageBubble
                      message={msg}
                      onRegenerate={isLastAssistant ? () => handleRegenerate(currentSessionId!) : undefined}
                      onEditResend={msg.role === 'user' ? handleEditResend : undefined}
                    />
                  </div>
                );
              })
            )}
            {/* M6：执行过程时间线（C-03，替代原 ThinkingBubble） */}
            {currentStreamState?.phase === 'thinking' && (
              <ThinkingTimeline lines={currentStreamState.thinkingLines} />
            )}
            {/* M6：流式输出（思考完成后替换时间线；C-02 Markdown 增量渲染） */}
            {currentStreamState?.phase === 'streaming' && (
              <StreamingBubble text={currentStreamState.streamingText} />
            )}
            {/* M6-36：执行已中断 + 重试（每会话最多一个断点） */}
            {interruptedTurns[currentSessionId ?? ''] && (
              <InterruptedBubble onRetry={() => handleRetry(currentSessionId!)} />
            )}
            <div ref={messagesEndRef} />
          </div>

          {/* C1：左缘记录式滚动条（悬停才可滑动；刻度悬停显示该轮内容预览） */}
          <TurnScrollbar containerRef={scrollRef} messages={messages} />

          {/* M5-1 + C1：非底部时显示圆形回底按钮（参考稿 ↓ 圆钮样式） */}
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
              aria-label="回到底部"
              className="absolute bottom-3 left-1/2 -translate-x-1/2 z-10 flex h-9 w-9 items-center justify-center rounded-full border border-line bg-surface text-ink-secondary shadow-1 transition-colors hover:text-ink animate-rise focus-ring"
            >
              <ArrowDown className="h-4 w-4" />
            </button>
          )}
        </div>

        {/* 输入区（R2/C-2：Composer 纯展示组件；模型选择槽由 page 注入——
            模型选择语义不变：localStorage travel.model / dropUp / 40005 拦截，R11） */}
        <Composer
          value={input}
          onChange={(v) => setDrafts((prev) => ({ ...prev, [activeDraftKey]: v }))}
          onSend={() => handleSend()}
          onStop={handleStop}
          showStop={
            sending
            && activeTurnRef.current?.sid === currentSessionId
            && currentStreamState?.phase === 'thinking'
          }
          canSend={!!input.trim() && !sending && !creatingSession}
          modelSlot={
            <ModelSelector value={modelPref.model} onChange={modelPref.select} dropUp compact />
          }
          textareaRef={textareaRef}
        />
      </div>

      {/* 窄屏会话抽屉（C-05） */}
      <Dialog open={drawerOpen} onClose={() => setDrawerOpen(false)} className="max-w-xs p-3" ariaLabel="会话列表">
        <SessionList
          sessions={sessionList.sessions}
          currentSessionId={currentSessionId}
          creatingSession={creatingSession}
          streamStates={chatStream.streamStates}
          completedTurns={completedTurns}
          editingSessionId={sessionList.editingSessionId}
          editingTitle={sessionList.editingTitle}
          pinnedIds={sessionList.pinnedIds}
          onTogglePin={sessionList.togglePin}
          onNewSession={handleNewSession}
          onSelect={handleSelectSession}
          onEnterSelect={(sid) => setCurrentSessionId(sid)}
          onStartEdit={sessionList.startEdit}
          onTitleChange={sessionList.changeEditingTitle}
          onSaveTitle={(sid) => {
            sessionList.saveTitle(sid);
            setDrawerOpen(false);
          }}
          onCancelEdit={sessionList.cancelEdit}
          onCloseSession={handleCloseSession}
        />
      </Dialog>
    </div>
  );
}

/** B3：消息列表是否以 user 消息结尾（用于判断最后一条 assistant） */
function isLastMessageUser(list: ChatMessage[]): boolean {
  return list.length > 0 && list[list.length - 1].role === 'user';
}

export default function ChatPage() {
  return <ChatContent />;
}
