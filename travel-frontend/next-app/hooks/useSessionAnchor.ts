'use client';

import { useCallback, useEffect, useState } from 'react';
import { anchorApi } from '@/lib/api';
import type { AnchorBrief } from '@/types';

/**
 * M23（E1/E2）：会话锚定状态管理。
 *
 * <p>per-turn truth：发送消息时携带消息内快照（anchors[sid].ids）；
 * 服务端存储仅作恢复（刷新/跨端）与"可选规划为空"判定。
 * toggle 为乐观更新——先改本地再 PUT，失败回滚。</p>
 */
export interface SessionAnchorState {
  ids: number[];
  briefs: Record<number, AnchorBrief>;
  loading: boolean;
}

export function useSessionAnchor(currentSessionId?: string | null) {
  const [anchors, setAnchors] = useState<Record<string, SessionAnchorState>>({});

  const stateOf = useCallback(
    (sid?: string | null): SessionAnchorState => (sid ? anchors[sid] ?? { ids: [], briefs: {}, loading: false } : { ids: [], briefs: {}, loading: false }),
    [anchors],
  );

  /** 进入会话时回读（GET 自动剔除已删除行程=自愈）。 */
  const load = useCallback(async (sid: string) => {
    setAnchors((prev) => ({ ...prev, [sid]: { ...(prev[sid] ?? { ids: [], briefs: {} }), loading: true } }));
    try {
      const res = await anchorApi.getAnchors(sid);
      const briefs: Record<number, AnchorBrief> = {};
      for (const b of res.data.data ?? []) {
        briefs[b.id] = b;
      }
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ids: (res.data.data ?? []).map((b) => b.id), briefs, loading: false },
      }));
    } catch {
      setAnchors((prev) => ({ ...prev, [sid]: { ids: [], briefs: {}, loading: false } }));
    }
  }, []);

  /** 勾选/取消（单锚定首发：勾选新项自动替换旧项；再点同项=移除）。 */
  const toggle = useCallback(async (sid: string, id: number) => {
    const prevIds = anchors[sid]?.ids ?? [];
    const next = prevIds.includes(id)
      ? prevIds.filter((x) => x !== id)
      : [id, ...prevIds.filter((x) => x !== id)].slice(0, 3);
    setAnchors((prev) => ({
      ...prev,
      [sid]: { ...(prev[sid] ?? { ids: [], briefs: {} }), ids: next },
    }));
    try {
      const res = await anchorApi.replaceAnchors(sid, next);
      const effective = res.data.data ?? next;
      // 以服务端有效集合校准（自愈剔除场景）
      setAnchors((prev) => ({
        ...prev,
        [sid]: { ...(prev[sid] ?? { ids: [], briefs: {} }), ids: effective },
      }));
    } catch {
      setAnchors((prev) => ({ ...prev, [sid]: { ...(prev[sid] ?? { ids: [], briefs: {} }), ids: prevIds } }));
    }
  }, [anchors]);

  /** 行程列表供面板展示（复用行程分页接口；面板内分页拉取）。 */
  useEffect(() => {
    if (currentSessionId && anchors[currentSessionId] === undefined) {
      void load(currentSessionId);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentSessionId]);

  return { anchors, stateOf, load, toggle };
}
