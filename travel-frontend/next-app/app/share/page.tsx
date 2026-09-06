'use client';

import { Suspense, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { shareApi } from '@/lib/api';
import type { ItineraryResponse } from '@/types';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/components/ui/error-state';

/**
 * M25（E5）：行程分享公开只读页（/share?token=...）。
 *
 * <p>授权=不可伪造的签名 token（无认证；匿名限流面覆盖）。
 * 仅渲染行程内容（标题/目的地/天数/每日景点），不含任何用户信息。</p>
 */
function ShareContent() {
  const params = useSearchParams();
  const token = params.get('token') ?? '';
  const [data, setData] = useState<ItineraryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!token) {
      setError('分享链接无效');
      setLoading(false);
      return;
    }
    let cancelled = false;
    shareApi
      .getShared(token)
      .then((res) => {
        if (!cancelled) setData(res.data.data);
      })
      .catch(() => {
        if (!cancelled) setError('分享链接无效或已过期');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (loading) {
    return (
      <div className="mx-auto max-w-2xl space-y-3 p-6">
        <Skeleton className="h-8 w-2/3" />
        <Skeleton className="h-4 w-1/3" />
        <Skeleton className="h-40 w-full" />
      </div>
    );
  }
  if (error || !data) {
    return (
      <div className="mx-auto max-w-2xl p-10 text-center text-ink-faint">
        {error ?? '分享内容不可用'}
      </div>
    );
  }

  const dayPlans = data.dayPlans ?? [];
  return (
    <div className="mx-auto max-w-2xl space-y-4 p-6">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{data.title}</h1>
        <p className="text-sm text-ink-faint">
          {data.destination}
          {data.days != null && ` · ${data.days} 天`}
          {data.budget != null && ` · 预算 ¥${data.budget}`}
          {data.party && ` · ${data.party}`}
        </p>
        <p className="text-xs text-ink-faint">来自 Travel Planner 的行程分享（只读）</p>
      </header>
      {dayPlans.map((day, idx) => (
        <section key={idx} className="rounded-xl border border-line bg-surface p-4">
          <h2 className="mb-2 text-sm font-medium">
            第 {day.day ?? idx + 1} 天{day.summary ? ` · ${day.summary}` : ''}
          </h2>
          <ul className="space-y-1">
            {(day.attractions ?? []).map((a, ai) => (
              <li key={ai} className="flex items-baseline gap-2 text-sm">
                <span className="text-ink-faint">{a.timeSlot ?? ''}</span>
                <span>{a.name}</span>
                {a.notes && <span className="text-xs text-ink-faint">{a.notes}</span>}
              </li>
            ))}
          </ul>
        </section>
      ))}
      <footer className="pt-2 text-center text-xs text-ink-faint">
        由 AI Travel Planner 生成 · <a href="/" className="underline">我也想试试</a>
      </footer>
    </div>
  );
}

export default function SharePage() {
  return (
    <Suspense fallback={null}>
      <ShareContent />
    </Suspense>
  );
}
