'use client';

import { useCallback, useEffect, useState } from 'react';
import { MapPin, Calendar, DollarSign, Clock } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import type { ItineraryResponse } from '@/types';
import { formatCurrency, formatDate } from '@/lib/utils';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/components/ui/error-state';
import { Dialog, type DialogOriginRect } from '@/components/ui/dialog';
import dynamic from 'next/dynamic';

const BudgetSection = dynamic(() => import('@/components/feature/budget-section').then((m) => m.BudgetSection), {
  ssr: false,
  loading: () => <Skeleton className="h-56 w-full" />,
});
const MarkmapView = dynamic(() => import('@/components/markmap-view').then((m) => m.MarkmapView), {
  ssr: false,
  loading: () => <Skeleton className="h-64 w-full" />,
});

interface Props {
  itineraryId: number | null;
  onClose: () => void;
  /** C5：Container Transform 起点矩形（被点击卡片的位置），由列表页捕获传入 */
  originRect?: DialogOriginRect | null;
}

/** 行程名片弹窗（F103）+ C5 容器变换：以被点击卡片为起点展开/收回；×/遮罩/Esc 关闭 */
export function ItineraryCardModal({ itineraryId, onClose, originRect }: Props) {
  const [data, setData] = useState<ItineraryResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (itineraryId == null) return;
    setLoading(true);
    setError(null);
    try {
      const res = await itineraryApi.getById(itineraryId);
      setData(res.data.data);
    } catch (err) {
      setError(getErrorMessage(err));
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [itineraryId]);

  useEffect(() => {
    if (itineraryId != null) {
      void load();
    } else {
      setData(null);
      setError(null);
    }
  }, [itineraryId, load]);

  /** 行程名片弹窗（F103）+ B3 迁移 ui/dialog 基座（F-09：Esc/滚动锁/焦点圈禁/进出动画）；
      关闭方式保持：× / 点击遮罩 / Esc */
  return (
    <Dialog open={itineraryId != null} onClose={onClose} ariaLabel="行程详情" originRect={originRect}>
      {loading && (
        <div className="space-y-3">
          <Skeleton className="h-7 w-1/2" />
          <Skeleton className="h-24 w-full" />
          <Skeleton className="h-48 w-full" />
        </div>
      )}

      {!loading && error && (
        <ErrorState message={error} onReset={load} />
      )}

      {!loading && !error && data && (
          <>
            <h2 className="text-xl font-bold mb-4">{data.title}</h2>

            <div className="grid grid-cols-2 gap-3 mb-5 md:grid-cols-4">
              <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                <MapPin className="h-4 w-4 text-brand-500 mb-1" />
                <p className="text-xs text-slate-400">目的地</p>
                <p className="font-medium">{data.destination}</p>
              </div>
              <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                <Calendar className="h-4 w-4 text-brand-500 mb-1" />
                <p className="text-xs text-slate-400">天数</p>
                <p className="font-medium">{data.days} 天</p>
              </div>
              <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                <DollarSign className="h-4 w-4 text-brand-500 mb-1" />
                <p className="text-xs text-slate-400">估算费用</p>
                <p className="font-medium">{formatCurrency(data.estimatedCost)}</p>
              </div>
              <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 p-3">
                <Clock className="h-4 w-4 text-brand-500 mb-1" />
                <p className="text-xs text-slate-400">生成时间</p>
                <p className="font-medium text-sm">{formatDate(data.generatedAt)}</p>
              </div>
            </div>

            {data.dayPlans && data.dayPlans.length > 0 && (
              <div className="mb-5">
                <h3 className="font-semibold mb-2">每日行程</h3>
                <div className="space-y-3">
                  {data.dayPlans.map((day) => (
                    <div key={day.day} className="rounded-xl border border-slate-200 dark:border-slate-700 p-3">
                      <p className="font-medium mb-1">
                        第 {day.day} 天{day.date ? ` · ${day.date}` : ''}
                      </p>
                      <p className="text-sm text-slate-500 dark:text-slate-400 mb-2">{day.summary}</p>
                      {(day.attractions || []).map((attr, idx) => (
                        <div key={idx} className="flex items-start gap-2 text-sm">
                          <span className="text-brand-500 text-xs mt-0.5">{attr.timeSlot}</span>
                          <span className="font-medium">{attr.name}</span>
                          {attr.notes && <span className="text-slate-400">{attr.notes}</span>}
                        </div>
                      ))}
                      {day.hotelSuggestion && (
                        <p className="text-xs text-slate-400 mt-2">🏨 {day.hotelSuggestion}</p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {data.mindmap && (
              <div className="mb-5">
                <h3 className="font-semibold mb-2">思维导图</h3>
                <div className="h-64 rounded-xl border border-slate-200 dark:border-slate-700 p-2">
                  <MarkmapView data={data.mindmap} />
                </div>
              </div>
            )}

            {/* R3：预算概览统一组件（含空态） */}
            <BudgetSection
              estimatedCost={data.estimatedCost}
              dayPlans={data.dayPlans}
              bodyClassName="rounded-xl border border-line p-3"
            />
        </>
      )}
    </Dialog>
  );
}
