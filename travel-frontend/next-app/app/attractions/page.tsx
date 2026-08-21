'use client';

import { useState } from 'react';
import { toast } from 'sonner';
import { Loader2, Search, MapPin, Star, Ticket } from 'lucide-react';
import { attractionApi, getErrorMessage } from '@/lib/api';
import type { Attraction, PageResult, SearchResult } from '@/types';
import { formatCurrency } from '@/lib/utils';
import { ListState } from '@/components/ui/list-state';
import { PagedSelect } from '@/components/ui/paged-options';
import { takePrefetch } from '@/lib/prefetch';
import { SmartImage } from '@/components/ui/smart-image';

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

const cityOptions = ['北京', '上海', '广州', '深圳', '杭州', '成都', '西安', '厦门', '南京', '重庆', '武汉', '长沙'];
const ALL_CITY = '__all__';
const PAGE_SIZE = 12;

export default function AttractionsPage() {
  const [query, setQuery] = useState('');
  const [ragType, setRagType] = useState('hybrid');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [list, setList] = useState<Attraction[]>([]);
  const [cities, setCities] = useState<string[]>([]);
  const [allSelected, setAllSelected] = useState(false);
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<'search' | 'browse'>('search');

  const handleSearch = async () => {
    if (!query.trim()) return;
    setLoading(true);
    try {
      const res = await attractionApi.search(query, ragType, 10);
      setResults(res.data.data || []);
      toast.success(`检索到 ${res.data.data?.length || 0} 条结果`);
    } catch (err) {
      toast.error('检索失败: ' + getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const loadAll = async (targetPage = 1, cityList = cities, all = allSelected) => {
    // F102：命中预取缓存则直接展示
    const cached = takePrefetch<PageResult<Attraction>>(`attractions:${targetPage}:${PAGE_SIZE}`);
    if (cached) {
      setError(null);
      setList(cached.list || []);
      setTotalPages(Math.max(1, cached.totalPages || 1));
      setPage(targetPage);
      setLoading(false);
      return;
    }
    setError(null);
    setLoading(true);
    try {
      // F101：多城市逗号分隔传给后端（空数组=全部）
      const cityQuery = !all && cityList.length > 0 ? cityList.join(',') : undefined;
      const res = await attractionApi.list(cityQuery, undefined, targetPage, PAGE_SIZE);
      const data = res.data.data;
      setList(data?.list || []);
      setTotalPages(Math.max(1, data?.totalPages || 1));
      setPage(targetPage);
    } catch (err) {
      const message = getErrorMessage(err);
      setError(message);
      toast.error('加载失败: ' + message);
    } finally {
      setLoading(false);
    }
  };

  const switchToBrowse = () => {
    setMode('browse');
    setCities([]);
    setAllSelected(false);
    loadAll(1, [], false);
  };

  // F102：多选城市；"全部"与其他城市互斥（选中全部→其他取消；点城市→全部取消）
  const onToggleCity = (v: string) => {
    if (v === ALL_CITY) {
      const nextAll = !allSelected;
      setAllSelected(nextAll);
      setCities([]);
      loadAll(1, [], nextAll);
      return;
    }
    const next = allSelected ? [v] : cities.includes(v)
      ? cities.filter((c) => c !== v)
      : [...cities, v];
    setAllSelected(false);
    setCities(next);
    loadAll(1, next, false);
  };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">景点搜索</h1>

      {/* 模式切换 */}
      <div className="flex gap-2 mb-4">
        <button
          onClick={() => setMode('search')}
          className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            mode === 'search' ? 'bg-brand-500 text-white' : 'bg-slate-100 dark:bg-slate-800'
          }`}
        >
          检索
        </button>
        <button
          onClick={switchToBrowse}
          className={`px-4 py-1.5 rounded-lg text-sm font-medium transition-colors ${
            mode === 'browse' ? 'bg-brand-500 text-white' : 'bg-slate-100 dark:bg-slate-800'
          }`}
        >
          浏览全部
        </button>
      </div>

      {mode === 'search' ? (
        <>
          <div className="glass rounded-xl p-4 mb-4">
            <div className="flex gap-2 mb-3">
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
                placeholder="搜索景点，如：北京 文化景点"
                className="flex-1 px-4 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 outline-none"
              />
              <select
                value={ragType}
                onChange={(e) => setRagType(e.target.value)}
                className="px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent text-sm"
              >
                {ragTypes.map((t) => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
              <button
                onClick={handleSearch}
                disabled={loading}
                className="px-4 py-2 rounded-lg bg-brand-500 text-white hover:bg-brand-600 disabled:opacity-50 magnetic"
              >
                {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Search className="h-4 w-4" />}
              </button>
            </div>
          </div>

          <div className="space-y-3">
            {results.map((r, idx) => (
              <div key={idx} className="glass rounded-xl p-4">
                {/* M3-21：检索图无图占位一致（统一 SmartImage 首字兜底） */}
                <SmartImage
                  src={r.imageUrl}
                  alt={r.title}
                  fallbackText={r.title}
                  className="h-32 w-full rounded-lg mb-3"
                />
                <div className="flex items-start justify-between mb-1">
                  <h3 className="font-semibold">{r.title}</h3>
                  <span className="text-xs px-2 py-0.5 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-600">
                    {r.source} · {r.score.toFixed(4)}
                  </span>
                </div>
                <p className="text-sm text-slate-500 dark:text-slate-400">{r.snippet}</p>
              </div>
            ))}
            {results.length === 0 && !loading && (
              <p className="text-center py-10 text-slate-400">输入关键词开始搜索</p>
            )}
          </div>
        </>
      ) : (
        <>
          {/* 城市筛选（F102：分页下拉多选，默认每页 10；"全部"与其他城市互斥） */}
          <div className="mb-4 max-w-sm">
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

          <ListState
            loading={loading}
            error={error}
            empty={list.length === 0}
            emptyMessage="暂无数据"
            onRetry={() => {
              setError(null);
              loadAll();
            }}
            skeletonCount={6}
          >
            <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              {list.map((a) => (
                <div key={a.id} className="glass rounded-xl p-4 magnetic">
                  {/* F121：景点封面（懒加载 + 失败首字占位） */}
                  <SmartImage
                    src={a.imageUrl}
                    alt={a.name}
                    fallbackText={a.name}
                    className="h-36 w-full rounded-lg mb-3"
                    zoomable
                  />
                  <h3 className="font-semibold mb-1">{a.name}</h3>
                  <div className="flex items-center gap-2 text-xs text-slate-400 mb-2">
                    <MapPin className="h-3 w-3" /> {a.city}
                    <span className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800">
                      {typeLabels[a.type] || a.type}
                    </span>
                  </div>
                  <p className="text-sm text-slate-500 dark:text-slate-400 line-clamp-2 mb-2">{a.description}</p>
                  <div className="flex items-center gap-3 text-xs">
                    <span className="flex items-center gap-0.5">
                      <Star className="h-3 w-3 text-yellow-400" /> {a.rating}
                    </span>
                    <span className="flex items-center gap-0.5">
                      <Ticket className="h-3 w-3" /> {a.freeEntry ? '免费' : formatCurrency(Number(a.ticketPrice))}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          </ListState>

          {/* 分页 */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 mt-6">
              <button
                disabled={page <= 1 || loading}
                onClick={() => loadAll(page - 1)}
                className="px-3 py-1.5 rounded-lg text-sm bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
              >
                上一页
              </button>
              <span className="text-sm text-slate-500">
                {page} / {totalPages}
              </span>
              <button
                disabled={page >= totalPages || loading}
                onClick={() => loadAll(page + 1)}
                className="px-3 py-1.5 rounded-lg text-sm bg-slate-100 dark:bg-slate-800 disabled:opacity-40"
              >
                下一页
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}
