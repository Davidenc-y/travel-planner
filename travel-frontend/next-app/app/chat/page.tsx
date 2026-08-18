'use client';

import { useEffect, useState, useRef } from 'react';
import { useRouter } from 'next/navigation';
import { toast } from 'sonner';
import { Loader2, Send, Plus, MessageSquare } from 'lucide-react';
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

    try {
      const res = await chatApi.sendMessage(currentSessionId, input);
      const aiMsg: ChatMessage = {
        sessionId: currentSessionId,
        role: 'assistant',
        content: res.data.data.response,
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
            <button
              key={s.sessionId}
              onClick={() => setCurrentSessionId(s.sessionId)}
              className={cn(
                'w-full flex items-center gap-2 px-3 py-2 rounded-lg text-sm text-left transition-colors',
                currentSessionId === s.sessionId
                  ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
                  : 'hover:bg-slate-100 dark:hover:bg-slate-800'
              )}
            >
              <MessageSquare className="h-4 w-4 flex-shrink-0" />
              <span className="truncate">{s.title}</span>
            </button>
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
