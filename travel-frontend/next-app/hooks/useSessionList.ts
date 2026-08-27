'use client';

import { useCallback, useRef, useState } from 'react';
import { toast } from 'sonner';
import { chatApi, getErrorMessage } from '@/lib/api';
import type { ChatSession } from '@/types';
import { takePrefetch } from '@/lib/prefetch';

/**
 * M6-58/T10：会话列表领域 hook（CRUD + 置顶 + 标题 + 断点恢复依赖列表）。
 *
 * <p>从 chat/page.tsx 迁出：loadSessions（F102 预取缓存 / force 强制刷新）、
 * M6-49 置顶、M6-50 首条消息真实创建后的列表收口、M5-1 标题编辑（Esc 取消
 * 防误保存）、M4-9 显式结束会话，语义与迁移前一致。</p>
 */
export function useSessionList(userId: number | null) {
  const [sessions, setSessions] = useState<ChatSession[]>([]);
  const [loading, setLoading] = useState(true);
  // M5-1：标题编辑态
  const [editingSessionId, setEditingSessionId] = useState<string | null>(null);
  const [editingTitle, setEditingTitle] = useState('');
  const titleSavingRef = useRef(false);
  // M5-1：Esc 取消编辑后，输入框卸载触发的 onBlur 不得误保存
  const cancelEditRef = useRef(false);
  // M6-50：首条消息发送中才真实创建的会话（完成后再拉取列表，避免提前出现）
  const pendingNewSessionRef = useRef<string | null>(null);

  const loadSessions = useCallback(async (force = false) => {
    if (!userId) return;
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
      const res = await chatApi.listSessions(userId);
      setSessions(res.data.data || []);
    } catch {
      // ignore
    } finally {
      setLoading(false);
    }
  }, [userId]);

  // M6-49：会话置顶（最后消息时间最新；配合后端排序，发送完成后即时生效）
  const moveSessionToTop = useCallback((sid: string) => {
    setSessions((prev) => {
      const idx = prev.findIndex((s) => s.sessionId === sid);
      if (idx <= 0) return prev;
      const next = [...prev];
      const [s] = next.splice(idx, 1);
      return [s, ...next];
    });
  }, []);

  // M6-50：轮次完成后的会话列表收口——新建会话拉取权威列表（标题已由后端
  // 首条消息联动生成）；既有会话仅置顶（避免多余请求）
  const finalizeTurnSession = useCallback((sid: string) => {
    if (pendingNewSessionRef.current === sid) {
      pendingNewSessionRef.current = null;
      loadSessions(true);
    } else {
      moveSessionToTop(sid);
    }
  }, [loadSessions, moveSessionToTop]);

  /** M6-50/M6-60：真实创建会话（调用后端；仅首条消息发送时触发） */
  const createSession = useCallback(async (uid: number): Promise<string | null> => {
    try {
      const res = await chatApi.createSession(uid);
      const sid = res.data.data;
      pendingNewSessionRef.current = sid;
      // M6-60：创建成功立即加入左侧列表（思考期间切走也能点回在途会话）；
      // 标题用占位符，轮次完成后 finalizeTurnSession 拉取权威列表替换（标题后端联动）
      setSessions((prev) => {
        if (prev.some((s) => s.sessionId === sid)) return prev;
        return [{
          id: 0, // 占位 id：列表操作均以 sessionId 为准，最终由权威列表替换
          sessionId: sid,
          userId: uid,
          title: '新会话…',
          status: 'ACTIVE',
          createdAt: new Date().toISOString(),
        }, ...prev];
      });
      return sid;
    } catch (err: any) {
      toast.error('创建会话失败: ' + getErrorMessage(err));
      return null;
    }
  }, []);

  /** 首条消息标题联动：把后端返回的 sessionTitle 同步到列表 */
  const updateSessionTitle = useCallback((sid: string, title: string) => {
    setSessions((prev) => prev.map((s) =>
      s.sessionId === sid ? { ...s, title } : s));
  }, []);

  // M5-1：双击编辑标题
  const startEdit = useCallback((session: ChatSession) => {
    cancelEditRef.current = false;
    setEditingSessionId(session.sessionId);
    setEditingTitle(session.title);
  }, []);

  const cancelEdit = useCallback(() => {
    cancelEditRef.current = true;
    setEditingSessionId(null);
    setEditingTitle('');
  }, []);

  const changeEditingTitle = useCallback((value: string) => {
    setEditingTitle(value);
  }, []);

  const saveTitle = useCallback(async (sid: string) => {
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
  }, [editingTitle]);

  /** M4-9：显式结束会话（归档+收口摘要）；返回是否已结束（调用方清理当前会话态） */
  const closeSession = useCallback(async (sid: string): Promise<boolean> => {
    if (!confirm('确定结束该会话？结束后将不再出现在列表中（历史仍可读）。')) return false;
    try {
      await chatApi.closeSession(sid);
      toast.success('会话已结束');
      loadSessions(true);
      return true;
    } catch (err: any) {
      toast.error('结束会话失败: ' + getErrorMessage(err));
      return false;
    }
  }, [loadSessions]);

  return {
    sessions,
    loading,
    editingSessionId,
    editingTitle,
    loadSessions,
    createSession,
    finalizeTurnSession,
    updateSessionTitle,
    startEdit,
    cancelEdit,
    changeEditingTitle,
    saveTitle,
    closeSession,
  };
}
