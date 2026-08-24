'use client';

import { useEffect, useState, useRef } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { useSearchParams } from 'next/navigation';
import { decodeItineraryId } from '@/lib/url-guard';
import { toast } from 'sonner';
import { ArrowLeft, MapPin, Calendar, DollarSign, Clock } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ItineraryResponse } from '@/types';
import { formatCurrency, formatDate } from '@/lib/utils';
import { MarkmapView } from '@/components/markmap-view';
import dynamic from 'next/dynamic';
import { Skeleton } from '@/components/ui/skeleton';

const BudgetPie = dynamic(() => import('@/components/feature/budget-pie').then((m) => m.BudgetPie), {
  ssr: false,
  loading: () => <Skeleton className="h-56 w-full" />,
});

function ItineraryDetailContent() {
  const params = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [data, setData] = useState<ItineraryResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    // F94：地址栏参数名为短码（?i=38），还原为 itineraryId 再请求后端原始接口
const q = searchParams.get('itineraryId');
const rawId = q ? decodeItineraryId(q) : (Array.isArray(params.id) ? params.id[0] : params.id);
    if (rawId) {
      loadData();
    }
  }, [params.id, searchParams, isAuthenticated]);

  const loadData = async () => {
    try {
const q2 = searchParams.get('itineraryId');
const rawId = q2 ? decodeItineraryId(q2) : (Array.isArray(params.id) ? params.id[0] : params.id);
      const res = await itineraryApi.getById(Number(rawId));
      setData(res.data.data);
    } catch (err) {
      toast.error('加载失败: ' + getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-8 w-1/3" />
        <Skeleton className="h-24 w-full" />
        <Skeleton className="h-64 w-full" />
      </div>
    );
  }

  if (!data) {
    return <div className="text-center py-20 text-slate-400">行程不存在</div>;
  }

  return (
    <div>
      <button
        onClick={() => router.back()}
        className="flex items-center gap-1.5 text-sm text-slate-500 hover:text-brand-500 mb-4 transition-colors"
      >
        <ArrowLeft className="h-4 w-4" /> 返回
      </button>

      <h1 className="text-2xl font-bold mb-4">{data.title}</h1>

      {/* 基本信息 */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <div className="glass rounded-lg p-3">
          <MapPin className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-slate-400">目的地</p>
          <p className="font-medium">{data.destination}</p>
        </div>
        <div className="glass rounded-lg p-3">
          <Calendar className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-slate-400">天数</p>
          <p className="font-medium">{data.days} 天</p>
        </div>
        <div className="glass rounded-lg p-3">
          <DollarSign className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-slate-400">估算费用</p>
          <p className="font-medium">{formatCurrency(data.estimatedCost)}</p>
        </div>
        <div className="glass rounded-lg p-3">
          <Clock className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-slate-400">生成时间</p>
          <p className="font-medium text-sm">{formatDate(data.generatedAt)}</p>
        </div>
      </div>

      {/* 每日行程 */}
      {data.dayPlans && data.dayPlans.length > 0 && (
        <div className="mb-6">
          <h2 className="text-xl font-semibold mb-3">每日行程</h2>
          <div className="space-y-3">
            {data.dayPlans.map((day) => (
              <div key={day.day} className="glass rounded-xl p-4">
                <div className="flex items-center justify-between mb-3">
                  <h3 className="font-semibold">第 {day.day} 天{day.date ? ` · ${day.date}` : ''}</h3>
                  {day.transportMode && (
                    <span className="text-xs px-2 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800">
                      {day.transportMode}
                    </span>
                  )}
                </div>
                <p className="text-sm text-slate-500 dark:text-slate-400 mb-3">{day.summary}</p>
                {day.attractions && day.attractions.length > 0 && (
                  <div className="space-y-2">
                    {day.attractions.map((attr, idx) => (
                      <div key={idx} className="flex items-start gap-3 text-sm">
                        <span className="text-brand-500 font-mono text-xs mt-0.5">{attr.timeSlot}</span>
                        <div className="flex-1">
                          <span className="font-medium">{attr.name}</span>
                          {attr.notes && <span className="text-slate-400 ml-2">{attr.notes}</span>}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
                {day.hotelSuggestion && (
                  <p className="text-xs text-slate-400 mt-2">🏨 {day.hotelSuggestion}</p>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 思维导图 */}
      {data.mindmap && (
        <div>
          <h2 className="text-xl font-semibold mb-3">思维导图</h2>
          <div className="glass rounded-xl p-4 h-[400px]">
            <MarkmapView data={data.mindmap} />
          </div>
        </div>
      )}

      {/* F92：预算概览 */}
      {data.estimatedCost != null && (
        <div className="mt-6">
          <h2 className="text-xl font-semibold mb-3">预算概览</h2>
          <div className="glass rounded-xl p-4">
            <BudgetPie estimatedCost={data.estimatedCost} />
          </div>
        </div>
      )}
    </div>
  );
}

export default function ItineraryDetailPage() {
  return <ItineraryDetailContent />;
}
