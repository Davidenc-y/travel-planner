'use client';

import { useState } from 'react';
import { toast } from 'sonner';
import { Loader2, Search, MapPin, Star, Ticket } from 'lucide-react';
import { attractionApi, getErrorMessage } from '@/lib/api';
import type { Attraction, SearchResult } from '@/types';
import { formatCurrency } from '@/lib/utils';

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
const PAGE_SIZE = 12;

export default function AttractionsPage() {
  const [query, setQuery] = useState('');
  const [ragType, setRagType] = useState('hybrid');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [list, setList] = useState<Attraction[]>([]);
  const [city, setCity] = useState<string>('');
  const [page, setPage] = useState(1);
  const [totalPages, setTotalPages] = useState(1);
  const [loading, setLoading] = useState(false);
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

  const loadAll = async (targetPage = 1, targetCity = city) => {
    setLoading(true);
    try {
      const res = await attractionApi.list(targetCity || undefined, undefined, targetPage, PAGE_SIZE);
      const data = res.data.data;
      setList(data?.list || []);
      setTotalPages(Math.max(1, data?.totalPages || 1));
      setPage(targetPage);
    } catch (err) {
      toast.error('加载失败: ' + getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  };

  const switchToBrowse = () => {
    setMode('browse');
    setCity('');
    loadAll(1, '');
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
          RAG 检索
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
          {/* 城市筛选 */}
          <div className="flex flex-wrap gap-2 mb-4">
            <button
              onClick={() => loadAll(1, '')}
              className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                city === '' ? 'bg-brand-500 text-white' : 'bg-slate-100 dark:bg-slate-800'
              }`}
            >
              全部
            </button>
            {cityOptions.map((c) => (
              <button
                key={c}
                onClick={() => { setCity(c); loadAll(1, c); }}
                className={`px-3 py-1.5 rounded-lg text-sm transition-colors ${
                  city === c ? 'bg-brand-500 text-white' : 'bg-slate-100 dark:bg-slate-800'
                }`}
              >
                {c}
              </button>
            ))}
          </div>

          <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {list.map((a) => (
              <div key={a.id} className="glass rounded-xl p-4 magnetic">
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
            {list.length === 0 && !loading && (
              <p className="col-span-full text-center py-10 text-slate-400">暂无数据</p>
            )}
          </div>

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
