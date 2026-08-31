'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter, useSearchParams } from 'next/navigation';
import { toast } from 'sonner';
import { ArrowLeft, MapPin, Calendar, DollarSign, Clock, Maximize2, Copy } from 'lucide-react';
import { decodeItineraryId } from '@/lib/url-guard';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ItineraryResponse } from '@/types';
import { formatCurrency, formatDate } from '@/lib/utils';
import { MarkmapView } from '@/components/markmap-view';
import dynamic from 'next/dynamic';
import { Skeleton } from '@/components/ui/skeleton';
import { Button } from '@/components/ui/button';
import { Dialog } from '@/components/ui/dialog';
import { itineraryToMarkdown } from '@/lib/itinerary-markdown';

const BudgetSection = dynamic(() => import('@/components/feature/budget-section').then((m) => m.BudgetSection), {
  ssr: false,
  loading: () => <Skeleton className="h-56 w-full" />,
});

// B3/B4（04 §4.5 / 05 M6）：导出按钮组件（复制为 Markdown，clipboard + 降级与聊天复制同源）
function CopyMarkdownButton({ data }: { data: ItineraryResponse }) {
  const handleCopy = async () => {
    const md = itineraryToMarkdown(data);
    try {
      await navigator.clipboard.writeText(md);
      toast.success('行程 Markdown 已复制');
    } catch {
      try {
        const ta = document.createElement('textarea');
        ta.value = md;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        toast.success('行程 Markdown 已复制');
      } catch {
        toast.error('复制失败');
      }
    }
  };
  return (
    <Button variant="secondary" size="sm" onClick={handleCopy}>
      <Copy className="h-3.5 w-3.5" /> 复制 Markdown
    </Button>
  );
}

function ItineraryDetailContent() {
  const params = useParams();
  const searchParams = useSearchParams();
  const router = useRouter();
  const { isAuthenticated } = useAuth();
  const [data, setData] = useState<ItineraryResponse | null>(null);
  const [loading, setLoading] = useState(true);
  // B3（04 §4.5）：思维导图全屏查看
  const [mindmapFull, setMindmapFull] = useState(false);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    // F94：地址栏参数名为短码（?itineraryId=38），还原为 itineraryId 再请求后端原始接口
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

  // B3（04 §4.5）：返回安全化——无历史（直达 URL）时回列表页
  const handleBack = () => {
    if (typeof window !== 'undefined' && window.history.length > 1) {
      router.back();
    } else {
      router.replace('/itinerary');
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
    return <div className="text-center py-20 text-ink-faint">行程不存在</div>;
  }

  return (
    <div>
      <button
        onClick={handleBack}
        className="flex items-center gap-1.5 text-sm text-ink-secondary hover:text-brand-500 mb-4 transition-colors focus-ring rounded"
      >
        <ArrowLeft className="h-4 w-4" /> 返回
      </button>

      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <h1 className="text-2xl font-bold">{data.title}</h1>
        <CopyMarkdownButton data={data} />
      </div>

      {/* 基本信息（print 友好：grid 保持） */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-6">
        <div className="card p-3">
          <MapPin className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-ink-faint">目的地</p>
          <p className="font-medium">{data.destination}</p>
        </div>
        <div className="card p-3">
          <Calendar className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-ink-faint">天数</p>
          <p className="font-medium">{data.days} 天</p>
        </div>
        <div className="card p-3">
          <DollarSign className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-ink-faint">估算费用</p>
          <p className="font-medium">{formatCurrency(data.estimatedCost)}</p>
        </div>
        <div className="card p-3">
          <Clock className="h-4 w-4 text-brand-500 mb-1" />
          <p className="text-xs text-ink-faint">生成时间</p>
          <p className="font-medium text-sm">{formatDate(data.generatedAt)}</p>
        </div>
      </div>

      {/* 每日行程（B3/04 §4.5：时间线布局——左侧日期轴） */}
      {data.dayPlans && data.dayPlans.length > 0 && (
        <div className="mb-6">
          <h2 className="text-xl font-semibold mb-3">每日行程</h2>
          <div className="relative space-y-3 pl-5">
            <span aria-hidden className="absolute left-1.5 top-2 bottom-2 w-px bg-line" />
            {data.dayPlans.map((day) => (
              <div key={day.day} className="relative">
                <span
                  aria-hidden
                  className="absolute -left-[1.4rem] top-5 flex h-3 w-3 items-center justify-center rounded-full border-2 border-brand-500 bg-surface"
                />
                <div className="card p-4">
                  <div className="flex items-center justify-between mb-3">
                    <h3 className="font-semibold">第 {day.day} 天{day.date ? ` · ${day.date}` : ''}</h3>
                    {day.transportMode && (
                      <span className="text-xs px-2 py-0.5 rounded-full bg-surface-2 text-ink-secondary">
                        {day.transportMode}
                      </span>
                    )}
                  </div>
                  <p className="text-sm text-ink-secondary mb-3">{day.summary}</p>
                  {day.attractions && day.attractions.length > 0 && (
                    <div className="space-y-2">
                      {day.attractions.map((attr, idx) => (
                        <div key={idx} className="flex items-start gap-3 text-sm">
                          <span className="text-brand-500 font-mono text-xs mt-0.5">{attr.timeSlot}</span>
                          <div className="flex-1">
                            <span className="font-medium">{attr.name}</span>
                            {attr.notes && <span className="text-ink-faint ml-2">{attr.notes}</span>}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                  {day.hotelSuggestion && (
                    <p className="text-xs text-ink-faint mt-2">🏨 {day.hotelSuggestion}</p>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* 思维导图（B3：全屏查看按钮；"行程安排"段受后端 MindmapGenerator 缺陷影响，见 struct/13 §10.2） */}
      {data.mindmap && (
        <div>
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-xl font-semibold">思维导图</h2>
            <Button variant="secondary" size="sm" onClick={() => setMindmapFull(true)}>
              <Maximize2 className="h-3.5 w-3.5" /> 全屏查看
            </Button>
          </div>
          <div className="card p-4 h-[400px]">
            <MarkmapView data={data.mindmap} />
          </div>
        </div>
      )}

      {/* F92 + R3：预算概览统一组件（缺失时空态而非整块消失） */}
      <div className="mt-6">
        <h2 className="text-xl font-semibold mb-3">预算概览</h2>
        <div className="card p-4">
          <BudgetSection estimatedCost={data.estimatedCost} dayPlans={data.dayPlans} bodyClassName="" />        </div>
      </div>

      {/* 思维导图全屏弹窗 */}
      <Dialog open={mindmapFull} onClose={() => setMindmapFull(false)} className="h-[85vh] max-w-4xl" ariaLabel="思维导图全屏">
        {data.mindmap && (
          <div className="h-full">
            <h3 className="mb-2 font-semibold">{data.title} · 思维导图</h3>
            <div className="h-[calc(100%-2rem)]">
              <MarkmapView data={data.mindmap} />
            </div>
          </div>
        )}
      </Dialog>
    </div>
  );
}

export default function ItineraryDetailPage() {
  return <ItineraryDetailContent />;
}
