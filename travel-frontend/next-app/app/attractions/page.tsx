'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { Loader2, Search, MapPin, Star, Ticket, ArrowRight } from 'lucide-react';
import Link from 'next/link';
import { attractionApi, getErrorMessage } from '@/lib/api';
import type { Attraction, PageResult, SearchResult } from '@/types';
import { formatCurrency } from '@/lib/utils';
import { ListState } from '@/components/ui/list-state';
import { PagedSelect, PagedSingleSelect } from '@/components/ui/paged-options';
import { takePrefetch } from '@/lib/prefetch';
import { useApiQuery } from '@/lib/use-api-query';
import { SmartImage } from '@/components/ui/smart-image';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Pagination } from '@/components/ui/pagination';
import { Dialog } from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { ErrorState } from '@/components/ui/error-state';
import { PageHeader } from '@/components/ui/page-header';

const typeLabels: Record<string, string> = {
  CULTURE: '文化', NATURE: '自然', FOOD: '美食',
  SHOPPING: '购物', FAMILY: '亲子', LEISURE: '休闲',
};

const ragTypes = [
  { value: 'hybrid', label: '混合检索（推荐）' },
  { value: 'naive', label: '关键词' },
  { value: 'self_rag', label: '自适应' },
  { value: 'corrective_rag', label: '查询重写' },
];

// M5-1：后端城市列表失败时的降级常量（原 F101/F102 硬编码 12 城）
const FALLBACK_CITY_OPTIONS = ['北京', '上海', '广州', '深圳', '杭州', '成都', '西安', '厦门', '南京', '重庆', '武汉', '长沙'];
const ALL_CITY = '__all__';
const PAGE_SIZE = 12;

/** B3/B4（04 §4.6 / F-16）：统一景点卡片——检索结果与浏览模式共用一套视觉 */
function AttractionCard({ item, onOpen }: { item: Attraction; onOpen: (a: Attraction) => void }) {
  return (
    <div
      className="card overflow-hidden magnetic cursor-pointer animate-rise hover:shadow-2 transition-shadow"
      onClick={() => onOpen(item)}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && onOpen(item)}
    >
      {/* F121：景点封面（懒加载 + 失败首字占位） */}
      <SmartImage
        src={item.imageUrl}
        alt={item.name}
        fallbackText={item.name}
        className="h-36 w-full"
        zoomable
      />
      <div className="p-4">
        <h3 className="font-semibold mb-1">{item.name}</h3>
        <div className="flex items-center gap-2 text-xs text-ink-faint mb-2">
          <MapPin className="h-3 w-3" /> {item.city}
          <Badge tone="brand">{typeLabels[item.type] || item.type}</Badge>
        </div>
        <p className="text-sm text-ink-secondary line-clamp-2 mb-2">{item.description}</p>
        <div className="flex items-center gap-3 text-xs">
          <span className="flex items-center gap-0.5">
            <Star className="h-3 w-3 text-yellow-400" /> {item.rating}
          </span>
          <span className="flex items-center gap-0.5">
            <Ticket className="h-3 w-3" /> {item.freeEntry ? '免费' : formatCurrency(Number(item.ticketPrice))}
          </span>
        </div>
      </div>
    </div>
  );
}

/** B4（05 M5，F-16）：景点详情弹窗（数据 getById 已有；加载三态复用名片弹窗模式） */
function AttractionDetailDialog({
  attraction,
  onClose,
}: {
  attraction: Attraction | null;
  onClose: () => void;
}) {
  const [detail, setDetail] = useState<Attraction | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (attraction == null) {
      setDetail(null);
      setError(null);
      return undefined;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    setDetail(attraction);
    attractionApi.getById(attraction.id)
      .then((res) => {
        if (!cancelled) setDetail(res.data.data);
      })
      .catch((err) => {
        if (!cancelled) setError(getErrorMessage(err));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [attraction]);

  return (
    <Dialog open={attraction != null} onClose={onClose} ariaLabel="景点详情">
      {loading && !detail && (
        <div className="space-y-3">
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
      )}
      {!loading && error && !detail && <ErrorState message={error} onReset={onClose} />}
      {detail && (
        <div>
          <h2 className="text-xl font-bold mb-1 pr-8">{detail.name}</h2>
          <div className="flex items-center gap-2 text-xs text-ink-faint mb-3">
            <MapPin className="h-3 w-3" /> {detail.city}
            <Badge tone="brand">{typeLabels[detail.type] || detail.type}</Badge>
            <span className="flex items-center gap-0.5">
              <Star className="h-3 w-3 text-yellow-400" /> {detail.rating}
            </span>
            <span className="flex items-center gap-0.5">
              <Ticket className="h-3 w-3" /> {detail.freeEntry ? '免费' : formatCurrency(Number(detail.ticketPrice))}
            </span>
          </div>
          <SmartImage
            src={detail.imageUrl}
            alt={detail.name}
            fallbackText={detail.name}
            className="h-44 w-full rounded-lg mb-3"
            zoomable
          />
          <p className="text-sm text-ink-secondary whitespace-pre-wrap">{detail.description}</p>
          {detail.recommendedDuration && (
            <p className="mt-2 text-xs text-ink-faint">建议游玩时长：{detail.recommendedDuration}</p>
          )}
          {detail.tags && (
            <div className="mt-2 flex flex-wrap gap-1.5">
              {detail.tags.split(/[,，;；]/).filter(Boolean).map((tag) => (
                <Badge key={tag} tone="neutral">{tag}</Badge>
              ))}
            </div>
          )}
        </div>
      )}
    </Dialog>
  );
}

export default function AttractionsPage() {
  const [query, setQuery] = useState('');
  const [ragType, setRagType] = useState('hybrid');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [cities, setCities] = useState<string[]>([]);
  const [cityOptions, setCityOptions] = useState<string[]>(FALLBACK_CITY_OPTIONS);
  const [allSelected, setAllSelected] = useState(false);
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [page, setPage] = useState(1);
  const [searchLoading, setSearchLoading] = useState(false);
  const [mode, setMode] = useState<'search' | 'browse'>('search');
  // B4（M5）：详情弹窗
  const [detailItem, setDetailItem] = useState<Attraction | null>(null);

  // FE-P3.2.2：浏览列表取数迁移 useApiQuery（SWR-lite，与 itinerary 列表页同口径）——
  // 筛选/页码经 ref 供 fetcher 读取，loadPage 同步推进 ref 后 refetch；仅 browse 模式启用。
  const browseRef = useRef({ page: 1, cityQuery: undefined as string | undefined, type: undefined as string | undefined, hasFilter: false });

  // M5-1：城市下拉动态化——从后端加载全部城市，失败降级内置列表
  useEffect(() => {
    attractionApi.listCities()
      .then((res) => {
        const data = res.data.data || [];
        if (data.length > 0) setCityOptions(data);
      })
      .catch((err) => {
        toast.error('城市列表加载失败，已使用内置城市: ' + getErrorMessage(err));
      });
  }, []);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setSearchLoading(true);
    try {
      const res = await attractionApi.search(query, ragType, 10);
      setResults(res.data.data || []);
      toast.success(`检索到 ${res.data.data?.length || 0} 条结果`);
    } catch (err) {
      toast.error('检索失败: ' + getErrorMessage(err));
    } finally {
      setSearchLoading(false);
    }
  };

  const {
    data: browseData,
    loading,
    error,
    refetch,
  } = useApiQuery<PageResult<Attraction>>(
    useCallback(() => {
      const p = browseRef.current;
      // F102：命中预取缓存则直接展示（仅无筛选条件时，避免错误命中）
      if (!p.hasFilter) {
        const cached = takePrefetch<PageResult<Attraction>>(`attractions:${p.page}:${PAGE_SIZE}`);
        if (cached) return Promise.resolve(cached);
      }
      // F101：多城市逗号分隔传给后端（空数组=全部）；B4/M7：type 筛选贯通（后端参数已有）
      return attractionApi.list(p.cityQuery, p.type, p.page, PAGE_SIZE).then((res) => res.data.data);
    }, []),
    [],
    {
      enabled: mode === 'browse',
      // 与 itinerary 列表页同口径（FE-P3.2.1）：SWR 全量静默——有数据不闪 loading，
      // 静默失败保持旧列表；仅首次无缓存走骨架。分页/筛选/模式切换静默换数据的
      // 语义统一与现状骨架差异已披露（审计日志第 8 轮，待二次审批）。
      cacheKey: 'attractions:browse',
      staleMs: 24 * 60 * 60_000,
    }
  );
  const list = browseData?.list || [];
  const totalPages = Math.max(1, browseData?.totalPages || 1);

  const loadPage = useCallback(
    (targetPage: number, cityList: string[], all: boolean, type: string | undefined) => {
      const cityQuery = !all && cityList.length > 0 ? cityList.join(',') : undefined;
      browseRef.current = { page: targetPage, cityQuery, type, hasFilter: Boolean(type || all || cityList.length > 0) };
      setPage(targetPage);
      refetch();
    },
    [refetch]
  );

  // 首载失败 toast（现状语义保留）；静默刷新失败保持旧列表且不 toast
  useEffect(() => {
    if (error) toast.error('加载失败: ' + error);
  }, [error]);

  const switchToBrowse = () => {
    setMode('browse');
    setCities([]);
    setAllSelected(false);
    setTypeFilter(undefined);
    loadPage(1, [], false, undefined);
  };

  // F102：多选城市；"全部"与其他城市互斥（选中全部→其他取消；点城市→全部取消）
  const onToggleCity = (v: string) => {
    if (v === ALL_CITY) {
      const nextAll = !allSelected;
      setAllSelected(nextAll);
      setCities([]);
      loadPage(1, [], nextAll, typeFilter);
      return;
    }
    const next = allSelected ? [v] : cities.includes(v)
      ? cities.filter((c) => c !== v)
      : [...cities, v];
    setAllSelected(false);
    setCities(next);
    loadPage(1, next, false, typeFilter);
  };

  // B4/M7：类型筛选变更 → 回第 1 页
  const onTypeChange = (type: string | undefined) => {
    setTypeFilter(type);
    loadPage(1, cities, allSelected, type);
  };

  return (
    <div>
      <PageHeader
        title="景点发现"
        description="语义检索与城市浏览，发现你的下一站"
      />

      {/* 模式切换 */}
      <div className="flex gap-2 mb-4">
        <Button
          variant={mode === 'search' ? 'primary' : 'secondary'}
          size="sm"
          onClick={() => setMode('search')}
        >
          检索
        </Button>
        <Button
          variant={mode === 'browse' ? 'primary' : 'secondary'}
          size="sm"
          onClick={switchToBrowse}
        >
          浏览全部
        </Button>
      </div>

      {mode === 'search' ? (
        <>
          <div className="card p-4 mb-4">
            <div className="flex gap-2 mb-3">
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                placeholder="搜索景点，如：北京 文化景点"
                aria-label="搜索景点"
                className="flex-1 px-4 py-2 rounded-lg border border-line bg-transparent outline-none transition-all focus:border-brand-500 focus:ring-2 focus:ring-brand-500"
              />
              <select
                value={ragType}
                onChange={(e) => setRagType(e.target.value)}
                aria-label="检索策略"
                className="px-3 py-2 rounded-lg border border-line bg-transparent text-sm"
              >
                {ragTypes.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
              <Button onClick={handleSearch} disabled={searchLoading} aria-label="搜索">
                {searchLoading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
              </Button>
            </div>
          </div>

          <div className="space-y-3">
            {results.map((r, idx) => (
              <div key={idx} className="card p-4 flex gap-4 animate-rise">
                {/* M3-21：检索图无图占位一致（统一 SmartImage 首字兜底） */}
                <SmartImage
                  src={r.imageUrl}
                  alt={r.title}
                  fallbackText={r.title}
                  className="h-24 w-36 flex-shrink-0 rounded-lg"
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-start justify-between mb-1 gap-2">
                    <h3 className="font-semibold">{r.title}</h3>
                    <Badge tone="neutral">{r.source} · {r.score.toFixed(4)}</Badge>
                  </div>
                  <p className="text-sm text-ink-secondary line-clamp-2">{r.snippet}</p>
                </div>
              </div>
            ))}
            {results.length === 0 && !searchLoading && (
              <div className="card p-6 text-center text-ink-faint">
                <p>输入关键词开始搜索</p>
                <Link
                  href="/attractions"
                  className="mt-2 inline-flex items-center gap-1 text-sm text-brand-500 hover:underline"
                  onClick={(e) => {
                    e.preventDefault();
                    switchToBrowse();
                  }}
                >
                  或去浏览全部景点 <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </div>
            )}
          </div>
        </>
      ) : (
        <>
          {/* 筛选行（F102 城市多选 + B4/M7 类型筛选） */}
          <div className="mb-4 flex flex-col sm:flex-row gap-3 sm:items-center">
            <div className="max-w-sm flex-1">
              <PagedSelect
                multiple
                options={[
                  { value: ALL_CITY, label: '全部' },
                  ...cityOptions.map((c) => ({ value: c })),
                ]}
                selected={allSelected ? [ALL_CITY] : cities}
                onToggle={onToggleCity}
                placeholder={cities.length > 0 ? `已选 ${cities.length} 个城市` : '全部城市'}
                defaultPageSize={10}
              />
            </div>
            <div className="max-w-[12rem]">
              <PagedSingleSelect
                options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))}
                value={typeFilter}
                onChange={onTypeChange}
                placeholder="全部类型"
                defaultPageSize={10}
              />
            </div>
          </div>

          <ListState
            loading={loading}
            error={error}
            empty={list.length === 0}
            emptyMessage="暂无数据"
            onRetry={() => {
              refetch();
            }}
            skeletonCount={6}
          >
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              {list.map((a) => (
                <AttractionCard key={a.id} item={a} onOpen={setDetailItem} />
              ))}
            </div>
          </ListState>

          {/* B3：统一分页组件 */}
          <Pagination
            page={page}
            totalPages={totalPages}
            onChange={(p) => loadPage(p, cities, allSelected, typeFilter)}
            disabled={loading}
          />
        </>
      )}

      <AttractionDetailDialog attraction={detailItem} onClose={() => setDetailItem(null)} />
    </div>
  );
}
