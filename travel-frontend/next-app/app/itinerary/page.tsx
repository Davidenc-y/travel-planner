'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { toast } from 'sonner';
import { Plus } from 'lucide-react';
import { itineraryApi, getErrorMessage } from '@/lib/api';
import { ITINERARY_STATUS, ITINERARY_POLL_INTERVAL_MS, PAGE_SIZE_OPTIONS } from '@/lib/constants';
import { useAuth } from '@/lib/auth-context';
import { useApiQuery } from '@/lib/use-api-query';
import type { DialogOriginRect } from '@/components/ui/dialog';
import type { ItineraryResponse, PageResult } from '@/types';
import { decodeItineraryQuery } from '@/lib/url-guard';
import { ListState } from '@/components/ui/list-state';
import { takePrefetch } from '@/lib/prefetch';
import { ItineraryCardModal } from '@/components/feature/itinerary-card-modal';
import { ItineraryCard } from '@/components/feature/itinerary-card';
import { PageHeader } from '@/components/ui/page-header';
import { Pagination } from '@/components/ui/pagination';
import { Button } from '@/components/ui/button';
import { useConfirm } from '@/components/ui/confirm-dialog';

// F99：行程列表每页条数可选（默认 8）


function ItineraryListContent() {
  const router = useRouter();
  const { userId, isAuthenticated, mounted } = useAuth();
  const confirm = useConfirm();
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(8);
  // FE-P3.2.1：取数迁移 useApiQuery（SWR-lite）——page/size 经 ref 供 fetcher 读取，
  // loadPage 显式推进（同步置 ref 再 refetch，避免事件时序读到旧页码）。
  const pageRef = useRef(1);
  const sizeRef = useRef(8);

  const {
    data,
    loading,
    error,
    refetch,
  } = useApiQuery<PageResult<ItineraryResponse>>(
    useCallback(() => {
      // F102：命中预取缓存则直接展示（取走即删），避免切换卡顿
      const cached = takePrefetch<PageResult<ItineraryResponse>>(
        `itinerary:${pageRef.current}:${sizeRef.current}`
      );
      if (cached) return Promise.resolve(cached);
      return itineraryApi.list(pageRef.current, sizeRef.current).then((res) => res.data.data);
    }, []),
    [],
    {
      enabled: isAuthenticated && userId != null,
      // 现状语义锁定：loadData 从不置 loading（首载除外）——SWR 口径取全量静默
      //（有数据不闪 loading），仅首次无缓存走骨架；分页/轮询/删除重取均静默换数据。
      cacheKey: 'itinerary:list',
      staleMs: 24 * 60 * 60_000,
    }
  );
  const totalPages = Math.max(1, data?.totalPages || 1);
  // 登录态水合完成前维持骨架（现状：loading 初始 true）
  const listLoading = loading || !isAuthenticated || userId == null;

  const loadPage = useCallback(
    (targetPage: number, size: number) => {
      pageRef.current = targetPage;
      sizeRef.current = size;
      setPage(targetPage);
      setPageSize(size);
      refetch();
    },
    [refetch]
  );

  // 首载失败 toast（现状语义保留）；静默刷新失败保持旧数据且不 toast
  //（轮询失败不再连续刷屏，见审计日志第 7 轮披露）
  useEffect(() => {
    if (error) toast.error('加载失败: ' + error);
  }, [error]);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  // C5：Container Transform 起点——被点击卡片的矩形
  const [originRect, setOriginRect] = useState<DialogOriginRect | null>(null);
  const [resumingId, setResumingId] = useState<number | null>(null);

  useEffect(() => {
    // MM-10a 守卫加固：mounted=false（鉴权状态未知，SSR/首帧）时不触发 replace——
    // 消除 dev StrictMode 下"未鉴权误跳首页"竞态（生产语义不变：mounted 翻 true
    // 后本 effect 经依赖数组重跑，守卫按真实鉴权态执行）
    if (!mounted) {
      return;
    }
    if (!isAuthenticated) {
      router.replace('/');
      return;
    }
    // F98/M11-2：兼容“/itinerary?itineraryId=<加密令牌>”直达（聊天/规划跳转与旧书签），
    // 进入列表后自动打开对应行程名片弹窗（弹窗内已含路线地图）
    const q = new URLSearchParams(window.location.search).get('itineraryId');
    const directId = decodeItineraryQuery(q);
    if (directId != null) {
      setSelectedId(directId);
    }
  }, [userId, isAuthenticated, mounted]);

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
        refetch();
      }, ITINERARY_POLL_INTERVAL_MS);
    };
    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        if (timer) {
          clearTimeout(timer);
          timer = null;
        }
      } else if (!timer) {
        refetch();
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

  // B3（04 §4.4，F-08）：原生 confirm → useConfirm（文案保留原语义）
  const handleDelete = async (id: number) => {
    if (!(await confirm({ title: '确定删除此行程？', danger: true, confirmText: '删除' }))) return;
    try {
      await itineraryApi.delete(id);
      toast.success('删除成功');
      refetch();
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
      refetch();
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
        loading={listLoading}
        error={error}
        empty={!data || data.list.length === 0}
        emptyMessage="还没有行程，开始规划你的第一次旅行吧！"
        onRetry={() => {
          refetch();
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
        onChange={(p) => loadPage(p, pageSize)}
        onPageSizeChange={(size) => loadPage(1, size)}
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
