'use client';

import { useEffect, useState } from 'react';
import { itineraryApi } from '@/lib/api';
import type { AnchorBrief } from '@/types';

/**
 * M23（E1）：锚定面板数据——复用行程分页接口（本人行程，归属由后端 Bearer 决定）。
 * 单锚定首发（D-V8-3）：勾选新项自动替换。
 */
export function useAnchorPanel(open: boolean) {
  const [rows, setRows] = useState<AnchorBrief[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    itineraryApi
      .list(page, 8)
      .then((res) => {
        if (cancelled) return;
        const items = res.data.data?.list ?? [];
        setRows(
          items.map((it) => ({
            id: Number(it.id),
            title: String(it.title ?? it.destination ?? `行程 #${it.id}`),
            destination: it.destination ?? null,
            days: it.days ?? null,
            version: null,
          })),
        );
        setTotalPages(Math.max(1, res.data.data?.totalPages ?? 1));
      })
      .catch(() => {
        if (!cancelled) setError('行程列表加载失败');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, page]);

  return { rows, loading, error, page, setPage, totalPages };
}

/** M23（P-D）：done.suggestion 确认卡片的状态机（锚定询问）。 */
export function useAnchorSuggestion() {
  const [pending, setPending] = useState<{ itineraryId: number; title: string } | null>(null);
  const [dismissedFor, setDismissedFor] = useState<string | null>(null);

  const offer = (s: { itineraryId: number; title: string }, sessionId: string) => {
    if (dismissedFor !== sessionId) setPending(s);
  };
  const clear = () => setPending(null);
  const dismiss = (sessionId: string) => {
    setPending(null);
    setDismissedFor(sessionId);
  };
  return { pending, offer, clear, dismiss };
}
