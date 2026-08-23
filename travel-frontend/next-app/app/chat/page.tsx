'use client';

import { useEffect, useState, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, Send, Plus, MessageSquare, Archive } from 'lucide-react';
import { chatApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ChatMessage, ChatSession } from '@/types';
import { cn } from '@/lib/utils';
import { ChatMessageContent } from '@/components/feature/chat-message-content';
import { Skeleton } from '@/components/ui/skeleton';
import { takePrefetch } from '@/lib/prefetch';

function ChatContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      router.push('/login');
      return;
    }
    if (userId) loadSessions();
  }, [userId, isAuthenticated]);

  useEffect(() => {
    if (currentSessionId) loadHistory(currentSessionId);
  }, [currentSessionId]);

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  const loadSessions = async () => {
    // F102：命中预取缓存则直接展示
    const cached = takePrefetch<ChatSession[]>('chat:sessions');
    if (cached) {
      setSessions(cached);
      setLoading(false);
      return;
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
    try {
      const res = await chatApi.getHistory(sid);
      setMessages(res.data.data || []);
    } catch {
      // ignore
    }
  };

  const handleNewSession = async () => {
    try {
      const res = await chatApi.createSession(userId!, '旅游规划对话');
      const sid = res.data.data;
      setCurrentSessionId(sid);
      setMessages([]);
      loadSessions();
      toast.success('新会话已创建');
    } catch {
      toast.error('创建会话失败');
    }
  };

  /**
   * M4-9：带幂等键的发送——40904（同键处理中）3s 退避同键重试，最多 4 次尝试。
   * 兼容两种返回形态：HTTP 状态对齐（409 抛异常，err.response.data.code）与
   * 业务码双轨（200 + body.code）。
   */
  const sendMessageWithIdempotency = async (sid: string, text: string, key: string): Promise<string> => {
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
        return res.data.data.response;
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

  const handleSend = async () => {
    if (!input.trim() || sending) return;
    if (!currentSessionId) {
      toast.info('请先创建会话');
      return;
    }

    const userMsg: ChatMessage = {
      sessionId: currentSessionId,
      role: 'user',
      content: input,
    };
    setMessages((prev) => [...prev, userMsg]);
    setInput('');
    setSending(true);

    // M4-9：消息幂等键——超时/40904 重试携带同键，杜绝重复追加/双跑
    const clientMessageId = crypto.randomUUID();
    const messageText = input;
    try {
      const response = await sendMessageWithIdempotency(currentSessionId, messageText, clientMessageId);
      const aiMsg: ChatMessage = {
        sessionId: currentSessionId,
        role: 'assistant',
        content: response,
      };
      setMessages((prev) => [...prev, aiMsg]);
    } catch (err: any) {
      toast.error('发送失败: ' + getErrorMessage(err));
      setMessages((prev) => [...prev, {
        sessionId: currentSessionId!,
        role: 'assistant',
        content: '抱歉，处理您的请求时出现错误，请稍后重试。',
      }]);
    } finally {
      setSending(false);
    }
  };

  /** M4-9：显式结束会话（归档+收口摘要；不挂 beforeunload——刷新会误归档） */
  const handleCloseSession = async (sid: string) => {
    if (!confirm('确定结束该会话？结束后将不再出现在列表中（历史仍可读）。')) return;
    try {
      await chatApi.closeSession(sid);
      toast.success('会话已结束');
      if (currentSessionId === sid) {
        setCurrentSessionId(null);
        setMessages([]);
      }
      loadSessions();
    } catch (err: any) {
      toast.error('结束会话失败: ' + getErrorMessage(err));
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
          className="w-full flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 mb-3 magnetic"
        >
          <Plus className="h-4 w-4" /> 新会话
        </button>
        <div className="space-y-1">
          {sessions.map((s) => (
            <div key={s.sessionId} className="group relative">
              <button
                onClick={() => setCurrentSessionId(s.sessionId)}
                className={cn(
                  'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left transition-colors pr-8',
                  currentSessionId === s.sessionId
                    ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
                    : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                )}
              >
                <MessageSquare className="h-4 w-4 flex-shrink-0" />
                <span className="truncate">{s.title}</span>
              </button>
              {/* M4-9：显式结束会话（归档+收口；禁止 beforeunload 触发） */}
              <button
                title="结束会话"
                onClick={() => handleCloseSession(s.sessionId)}
                className="absolute right-1.5 top-1/2 -translate-y-1/2 p-1 rounded-md opacity-0 group-hover:opacity-100 hover:bg-red-50 dark:hover:bg-red-900/20 text-slate-400 hover:text-red-500 transition-all"
              >
                <Archive className="h-3.5 w-3.5" />
              </button>
            </div>
          ))}
          {sessions.length === 0 && (
            <p className="text-xs text-slate-400 text-center py-4">暂无会话</p>
          )}
        </div>
      </div>

      {/* 消息区 */}
      <div className="flex-1 flex flex-col glass rounded-xl overflow-hidden">
        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          {messages.length === 0 ? (
            <div className="flex flex-col items-center justify-center h-full text-slate-400">
              <MessageSquare className="h-12 w-12 mb-3 opacity-50" />
              <p>开始一段新的旅游规划对话</p>
            </div>
          ) : (
            messages.map((msg, idx) => (
              <div
                key={idx}
                className={cn('flex', msg.role === 'user' ? 'justify-end' : 'justify-start')}
              >
                <div
                  className={cn(
                    'max-w-[70%] rounded-2xl px-4 py-2.5',
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
              </div>
            ))
          )}
          {sending && (
            <div className="flex justify-start">
              <div className="bg-slate-100 dark:bg-slate-800 rounded-2xl rounded-bl-sm px-4 py-2.5">
                <Loader2 className="h-4 w-4 animate-spin" />
              </div>
            </div>
          )}
          <div ref={messagesEndRef} />
        </div>

        {/* 输入区 */}
        <div className="border-t border-slate-200 dark:border-slate-800 p-3 flex gap-2">
          <input
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && handleSend()}
            placeholder="输入消息..."
            className="flex-1 px-4 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
          />
          <button
            onClick={handleSend}
            disabled={!input.trim() || sending}
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
