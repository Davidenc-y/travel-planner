'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { anchorApi } from '@/lib/api';
import type { AnchorBrief } from '@/types';

/**
 * M23（E1/E2）：会话锚定状态管理。
 *
 * <p>per-turn truth：发送消息时携带消息内快照（anchors[sid].ids）；
 * 服务端存储仅作恢复（刷新/跨端）与"可选规划为空"判定。
 * toggle 为乐观更新——先改本地再 PUT，失败回滚。</p>
 *
 * <p>M28-2（实测加固）：① load 按会话维护请求代际，慢响应不得回覆盖新状态
 * （修复"加入后勾选闪没"类竞态）；② toggle/clear 改函数式读当前 ids
 * （消除陈旧闭包丢并发变更）；③ 锚定面板打开时强制回读校准（面板勾选与
 * 服务端真实锚定保持一致，任何来源的漂移在开面板瞬间收敛）。</p>
 */
export interface SessionAnchorState {
  ids: number[];
  briefs: Record<number, AnchorBrief>;
  loading: boolean;
}

const EMPTY: SessionAnchorState = { ids: [], briefs: {}, loading: false };

export function useSessionAnchor(currentSessionId?: string | null) {
  const [anchors, setAnchors] = useState<Record<string, SessionAnchorState>>({});
  // M28-2：每会话 GET 代际——仅最新请求的响应可落状态
  const loadGenRef = useRef<Record<string, number>>({});

  const stateOf = useCallback(
    (sid?: string | null): SessionAnchorState => (sid ? anchors[sid] ?? EMPTY : EMPTY),
    [anchors],
  );

  /** 回读锚定集合（GET 自动剔除已删除行程=自愈；代际防旧响应回覆盖）。 */
  const load = useCallback(async (sid: string) => {
    const gen = (loadGenRef.current[sid] ?? 0) + 1;
    loadGenRef.current[sid] = gen;
    setAnchors((prev) => ({ ...prev, [sid]: { ...(prev[sid] ?? EMPTY), loading: true } }));
    try {
      const res = await anchorApi.getAnchors(sid);
      if (loadGenRef.current[sid] !== gen) return; // 已有更新请求，丢弃本响应
      const briefs: Record<number, AnchorBrief> = {};
      for (const b of res.data.data ?? []) {
        briefs[b.id] = b;
      }
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ids: (res.data.data ?? []).map((b) => b.id), briefs, loading: false },
      }));
    } catch {
      if (loadGenRef.current[sid] !== gen) return;
      setAnchors((prev) => ({ ...prev, [sid]: { ...(prev[sid] ?? EMPTY), loading: false } }));
    }
  }, []);

  /** 勾选/取消（多锚定 ≤3：勾选新项追加，再点同项=移除）。函数式读当前 ids，防陈旧闭包。 */
  const toggle = useCallback(async (sid: string, id: number) => {
    let prevIds: number[] = [];
    let next: number[] = [];
    setAnchors((prev) => {
      prevIds = prev[sid]?.ids ?? [];
      next = prevIds.includes(id)
        ? prevIds.filter((x) => x !== id)
        : [id, ...prevIds.filter((x) => x !== id)].slice(0, 3);
      return { ...prev, [sid]: { ...(prev[sid] ?? EMPTY), ids: next } };
    });
    try {
      const res = await anchorApi.replaceAnchors(sid, next);
      const effective = res.data.data ?? next;
      // 以服务端有效集合校准（自愈剔除场景）；代际递增使在途 GET 不覆盖本次结果
      loadGenRef.current[sid] = (loadGenRef.current[sid] ?? 0) + 1;
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ...(prev[sid] ?? EMPTY), ids: effective },
      }));
    } catch {
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ...(prev[sid] ?? EMPTY), ids: prevIds },
      }));
    }
  }, []);

  /** 行程列表供面板展示（复用行程分页接口；面板内分页拉取）。 */
  useEffect(() => {
    if (currentSessionId && anchors[currentSessionId] === undefined) {
      void load(currentSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentSessionId]);

  /** M27（S6）：清空会话锚定（偏好-锚定冲突卡"按偏好规划"动作；乐观更新失败回滚）。 */
  const clear = useCallback(async (sid: string) => {
    let prevIds: number[] = [];
    setAnchors((prev) => {
      prevIds = prev[sid]?.ids ?? [];
      return { ...prev, [sid]: { ...(prev[sid] ?? EMPTY), ids: [] } };
    });
    try {
      await anchorApi.replaceAnchors(sid, []);
      loadGenRef.current[sid] = (loadGenRef.current[sid] ?? 0) + 1;
    } catch {
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ...(prev[sid] ?? EMPTY), ids: prevIds },
      }));
    }
  }, []);

  return { anchors, stateOf, load, toggle, clear };
}
