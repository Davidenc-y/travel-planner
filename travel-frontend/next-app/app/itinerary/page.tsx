'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { MapPin, Calendar, DollarSign, Trash2, Plus, RotateCcw, Loader2 } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import type { ItineraryResponse, PageResult } from '@/types';
import { formatCurrency, formatDate } from '@/lib/utils';
import { ListState } from '@/components/ui/list-state';
import { takePrefetch } from '@/lib/prefetch';
import { ItineraryCardModal } from '@/components/feature/itinerary-card-modal';

function ItineraryListContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const [data, setData] = useState<PageResult<ItineraryResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [pageSize, setPageSize] = useState(8);
  const [selectedId, setSelectedId] = useState<number | null>(null);
// F99：行程列表每页条数可选（默认 8）
const PAGE_SIZE_OPTIONS = [8, 10, 20, 50];

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    if (userId) {
      loadData();
    }
  }, [userId, isAuthenticated]);

const loadData = async (targetPage = 1, size = pageSize) => {
    // F102：命中预取缓存则直接展示（取走即删），避免切换卡顿
    const cached = takePrefetch<PageResult<ItineraryResponse>>(`itinerary:${targetPage}:${size}`);
    if (cached) {
      setError(null);
      setData(cached);
      setPage(targetPage);
      setPageSize(size);
      setTotalPages(Math.max(1, cached.totalPages || 1));
      setLoading(false);
      return;
    }
    setError(null);
    try {
      const res = await itineraryApi.list(userId!, targetPage, size);
      const d = res.data.data;
      setData(d);
      setPage(targetPage);
      setPageSize(size);
      setTotalPages(Math.max(1, d?.totalPages || 1));
    } catch (err: any) {
      const message = getErrorMessage(err);
      setError(message);
      toast.error('加载失败: ' + message);
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async (id: number) => {
    if (!confirm('确定删除此行程？')) return;
    try {
      await itineraryApi.delete(id);
      toast.success('删除成功');
      loadData();
    } catch (err) {
      toast.error('删除失败: ' + getErrorMessage(err));
    }
  };

  const [resumingId, setResumingId] = useState<number | null>(null);

  /** M4-9：断点续跑（仅 FAILED/僵尸 GENERATING；同步等待同 generate 交互形态） */
  const handleResume = async (id: number) => {
    if (resumingId) return;
    if (!confirm('从上次中断的位置继续生成？')) return;
    setResumingId(id);
    try {
      await itineraryApi.resume(id);
      toast.success('续跑完成');
      loadData();
    } catch (err) {
      toast.error('续跑失败: ' + getErrorMessage(err));
    } finally {
      setResumingId(null);
    }
  };

  /** M4-9：状态徽标（GENERATING 生成中/FAILED 失败可续） */
  const statusBadge = (status?: string) => {
    if (status === 'GENERATING') {
      return <span className="inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300"><Loader2 className="h-3 w-3 animate-spin" />生成中</span>;
    }
    if (status === 'FAILED') {
      return <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-300">失败·可续跑</span>;
    }
    return null;
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">我的行程</h1>
        <Link
          href="/plan"
          className="flex items-center gap-1.5 px-4 py-2 rounded-lg bg-brand-500 text-white text-sm font-medium hover:bg-brand-600 magnetic"
        >
          <Plus className="h-4 w-4" /> 新建行程
        </Link>
      </div>

      <ListState
        loading={loading}
        error={error}
        empty={!data || data.list.length === 0}
        emptyMessage="还没有行程，开始规划你的第一次旅行吧！"
        onRetry={() => {
          setError(null);
          loadData();
        }}
        skeletonCount={4}
      >
        <div className="grid gap-4 md:grid-cols-2">
          {(data?.list ?? []).map((item) => (
            <div
              key={item.id}
              className="glass rounded-xl p-5 hover:shadow-lg transition-all magnetic cursor-pointer"
              onClick={() => setSelectedId(item.id)}
            >
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-2">
                    <h3 className="font-semibold text-lg">{item.title}</h3>
                    {statusBadge(item.status)}
                  </div>
                  <div className="space-y-1 text-sm text-slate-500 dark:text-slate-400">
                    <p className="flex items-center gap-1.5">
                      <MapPin className="h-3.5 w-3.5" /> {item.destination}
                    </p>
                    <p className="flex items-center gap-1.5">
                      <Calendar className="h-3.5 w-3.5" /> {item.days} 天
                    </p>
                    <p className="flex items-center gap-1.5">
                      <DollarSign className="h-3.5 w-3.5" /> {formatCurrency(item.estimatedCost)}
                    </p>
                    <p className="text-xs">{formatDate(item.generatedAt)}</p>
                  </div>
                  {/* M4-9：失败/僵尸生成行提供断点续跑入口 */}
                  {(item.status === 'FAILED' || item.status === 'GENERATING') && (
                    <button
                      onClick={(e) => { e.stopPropagation(); handleResume(item.id); }}
                      disabled={resumingId === item.id}
                      className="mt-2 inline-flex items-center gap-1 px-3 py-1.5 rounded-lg text-xs font-medium bg-brand-500 text-white hover:bg-brand-600 disabled:opacity-50"
                    >
                      {resumingId === item.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
                      {resumingId === item.id ? '续跑中…' : '继续生成'}
                    </button>
                  )}
                </div>
                <button
                  onClick={(e) => { e.stopPropagation(); handleDelete(item.id); }}
                  className="p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 text-slate-400 hover:text-red-500 transition-colors"
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
            </div>
          ))}
        </div>
      </ListState>

      {/* F99：分页 + 每页条数可选（默认 8） */}
      {totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 mt-6">
          <label className="flex items-center gap-1 text-sm text-slate-500">
            每页
            <select
              value={pageSize}
              onChange={(e) => loadData(1, Number(e.target.value))}
              className="px-1.5 py-1 rounded border border-slate-200 dark:border-slate-700 bg-transparent text-sm"
            >
              {PAGE_SIZE_OPTIONS.map((n) => (
                <option key={n} value={n}>{n} 条</option>
              ))}
            </select>
          </label>
          <button
            disabled={page <= 1 || loading}
            onClick={() => loadData(page - 1)}
            className="px-3 py-1.5 rounded-lg text-sm bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
          >
            上一页
          </button>
          <span className="text-sm text-slate-500">{page} / {totalPages}</span>
          <button
            disabled={page >= totalPages || loading}
            onClick={() => loadData(page + 1)}
            className="px-3 py-1.5 rounded-lg text-sm bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
          >
            下一页
          </button>
        </div>
      )}

      {/* F103：行程名片弹窗（点击遮罩或右上角 × 关闭） */}
      <ItineraryCardModal itineraryId={selectedId} onClose={() => setSelectedId(null)} />
    </div>
  );
}

export default function ItineraryListPage() {
  return <ItineraryListContent />;
}
