'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { Plus } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { ITINERARY_STATUS, ITINERARY_POLL_INTERVAL_MS } from '@/lib/constants';
import { useAuth } from '@/lib/auth-context';
import type { DialogOriginRect } from '@/components/ui/dialog';
import type { ItineraryResponse, PageResult } from '@/types';
import { ListState } from '@/components/ui/list-state';
import { takePrefetch } from '@/lib/prefetch';
import { ItineraryCardModal } from '@/components/feature/itinerary-card-modal';
import { ItineraryCard } from '@/components/feature/itinerary-card';
import { PageHeader } from '@/components/ui/page-header';
import { Pagination } from '@/components/ui/pagination';
import { Button } from '@/components/ui/button';
import { useConfirm } from '@/components/ui/confirm-dialog';

// F99：行程列表每页条数可选（默认 8）
const PAGE_SIZE_OPTIONS = [8, 10, 20, 50];

function ItineraryListContent() {
  const router = useRouter();
  const { userId, isAuthenticated } = useAuth();
  const confirm = useConfirm();
  const [data, setData] = useState<PageResult<ItineraryResponse> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [pageSize, setPageSize] = useState(8);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  // C5：Container Transform 起点——被点击卡片的矩形
  const [originRect, setOriginRect] = useState<DialogOriginRect | null>(null);
  const [resumingId, setResumingId] = useState<number | null>(null);

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    if (userId) {
      loadData();
    }
  }, [userId, isAuthenticated]);

  // M6-54：存在生成中（GENERATING）的行程时自动轮询刷新（3s），生成完成后停止；
  // B3/PE-05（F-26）：页面不可见（切后台标签）时暂停轮询，回归可见时立即刷新一次
  useEffect(() => {
    if (!data) return undefined;
    const hasGenerating =
      data.list?.some((i) => i.status === ITINERARY_STATUS.GENERATING) ?? false;
    if (!hasGenerating) return undefined;

    let timer: ReturnType<typeof setTimeout> | null = null;
    const schedule = () => {
      timer = setTimeout(() => {
        loadData(page, pageSize);
      }, ITINERARY_POLL_INTERVAL_MS);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
      } else if (!timer) {
        loadData(page, pageSize);
      }
    };
    if (document.visibilityState === 'visible') {
      schedule();
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => {
      if (timer) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisibility);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, page, pageSize]);

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
    } catch (err: unknown) {
      const message = getErrorMessage(err);
      setError(message);
      toast.error('加载失败: ' + message);
    } finally {
      setLoading(false);
    }
  };

  // B3（04 §4.4，F-08）：原生 confirm → useConfirm（文案保留原语义）
  const handleDelete = async (id: number) => {
    if (!(await confirm({ title: '确定删除此行程？', danger: true, confirmText: '删除' }))) return;
    try {
      await itineraryApi.delete(id);
      toast.success('删除成功');
      loadData();
    } catch (err) {
      toast.error('删除失败: ' + getErrorMessage(err));
    }
  };

  /** M4-9：断点续跑（仅 FAILED/僵尸 GENERATING；同步等待同 generate 交互形态） */
  const handleResume = async (id: number) => {
    if (resumingId) return;
    if (!(await confirm({ title: '从上次中断的位置继续生成？', confirmText: '继续生成' }))) return;
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

  return (
    <div>
      <PageHeader
        title="我的行程"
        actions={
          <Link
            href="/plan"
            className="inline-flex"
          >
            <Button>
              <Plus className="h-4 w-4" /> 新建行程
            </Button>
          </Link>
        }
      />

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
            <ItineraryCard
              key={item.id}
              item={item}
              resuming={resumingId === item.id}
              onOpen={(rect) => {
                setOriginRect(rect);
                setSelectedId(item.id);
              }}
              onDelete={handleDelete}
              onResume={handleResume}
            />
          ))}
        </div>
      </ListState>

      {/* F99 + B3：统一分页组件（含每页条数） */}
      <Pagination
        page={page}
        totalPages={totalPages}
        onChange={(p) => loadData(p)}
        onPageSizeChange={(size) => loadData(1, size)}
        pageSize={pageSize}
        pageSizeOptions={PAGE_SIZE_OPTIONS}
        disabled={loading}
      />

      {/* F103 + C5：行程名片弹窗（以被点击卡片为起点的容器变换转场） */}
      <ItineraryCardModal itineraryId={selectedId} onClose={() => setSelectedId(null)} originRect={originRect} />
    </div>
  );
}

export default function ItineraryListPage() {
  return <ItineraryListContent />;
}
