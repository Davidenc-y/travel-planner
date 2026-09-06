'use client';

import { useEffect, useState } from 'react';
import dynamic from 'next/dynamic';
import { adminApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';
import { formatTokenCount } from '@/lib/usage-format';

// M27（S6）：recharts ~222KB 按需加载（降 /admin/reliability First Load JS；仅访问时拉取）
const ReliabilityCharts = dynamic(() => import('./charts'), {
  ssr: false,
  loading: () => <div className="h-64 animate-pulse rounded-xl bg-surface-2" />,
});

interface ModelRow {
  model: string;
  statuses: Record<string, number>;
  total: number;
}

interface NodeRow {
  node: string;
  count: number;
}

interface TrendPoint {
  date: string;
  tokens: number;
  traces: number;
}

interface DurationRow {
  model: string;
  count: number;
  avgDurationMs: number;
  p50DurationMs: number;
  p95DurationMs: number;
  maxDurationMs: number;
}

interface QuotaRow {
  api: string;
  dayUsed: number;
  dayLimit: number;
  monthUsed: number;
  monthLimit: number;
}

/**
 * M11-3 + M14-1c：可靠性看板（后端白名单门控；含 token 趋势/模型耗时/地图配额水位）。
 */
export default function ReliabilityPage() {
  const { isAuthenticated, isAdmin, mounted } = useAuth();
  const [days, setDays] = useState(7);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [stats, setStats] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (!mounted || !isAuthenticated || !isAdmin) return;
    setLoading(true);
    setError('');
    adminApi.reliabilityStats(days)
      .then((res) => {
        const d = res.data.data;
        if (!d) throw new Error('空响应');
        setStats(d);
      })
      .catch((err: unknown) => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [days, isAuthenticated, isAdmin, mounted]);

  const modelRows = (stats?.modelDistribution as ModelRow[] | undefined) ?? [];
  const nodeRows = (stats?.topNodes as NodeRow[] | undefined) ?? [];
  const trendData = (stats?.tokenDailyTrend as TrendPoint[] | undefined) ?? [];
  const durationRows = (stats?.modelDuration as DurationRow[] | undefined) ?? [];
  const quotaRows = (stats?.mapQuota as QuotaRow[] | undefined) ?? [];
  // M27（S5/E3 观测支撑）：焦点分布（focusDistribution 为 {DETOUR, MAINLINE} 计数）
  const focusRows = (stats?.focusDistribution as Record<string, number> | undefined) ?? {};
  const barData = modelRows.map((r) => ({
    name: r.model,
    total: r.total,
  }));
  const trendBarData = trendData.map((r) => ({
    name: r.date.slice(5),
    tokens: r.tokens,
  }));
  const tokenTotal = Number(stats?.tokensTotal ?? 0);

  // M27（S6）：非管理员直访防御（后端 40302 为权威；此处避免无谓请求与闪烁）
  if (mounted && (!isAuthenticated || !isAdmin)) {
    return (
      <div className="mx-auto max-w-6xl px-4 py-16 text-center">
        <p className="text-lg font-medium">无权访问</p>
        <p className="mt-2 text-sm text-ink-faint">可靠性看板仅对白名单管理员开放。</p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">可靠性看板</h1>
          <p className="text-sm text-ink-faint">
            t_agent_trace 聚合：grounding / retention / token 成本 / 模型耗时 / 地图配额
          </p>
        </div>
        <select
          aria-label="时间范围"
          value={days}
          onChange={(e) => setDays(Number(e.target.value))}
          className="rounded-lg border border-line bg-surface px-3 py-2 text-sm"
        >
          <option value={7}>近 7 天</option>
          <option value={30}>近 30 天</option>
        </select>
      </div>

      {loading && <div className="text-sm text-ink-faint">加载中…</div>}
      {error && <div className="rounded-lg border border-red-300 p-4 text-sm text-red-600">{error}</div>}
      {!loading && !error && stats && (
        <>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <Metric label="Trace 总数" value={String(stats.traceTotal ?? 0)} />
            <Metric label="Grounding 均值" value={fmt(stats.groundingRate)} />
            <Metric label="Retention 均值" value={fmt(stats.retentionRate)} />
            <Metric label="Degraded 次数" value={String(stats.degraded ?? 0)} />
            <Metric label="Token 总量" value={formatTokenCount(tokenTotal)} />
          </div>

          {/* M27（S5/E3 观测支撑）：焦点隔离观测卡（三闸门开启前的达标量化） */}
          <section className="rounded-xl border border-line bg-surface p-4">
            <h2 className="mb-3 text-sm font-medium">焦点隔离观测（E3 观测期）</h2>
            <div className="grid gap-3 sm:grid-cols-3">
              <div className="rounded-lg bg-surface-2 p-3">
                <p className="text-xs text-ink-faint">DETOUR 样本（门槛 ≥30）</p>
                <p className="text-lg font-semibold">{focusRows.DETOUR ?? 0}</p>
              </div>
              <div className="rounded-lg bg-surface-2 p-3">
                <p className="text-xs text-ink-faint">主线样本（MAINLINE）</p>
                <p className="text-lg font-semibold">{focusRows.MAINLINE ?? 0}</p>
              </div>
              <div className="rounded-lg bg-surface-2 p-3">
                <p className="text-xs text-ink-faint">隔离生效次数（开关开启后）</p>
                <p className="text-lg font-semibold">{String(stats.detourIsolated ?? 0)}</p>
              </div>
            </div>
            <p className="mt-2 text-xs text-ink-faint">
              判定来自 t_agent_trace.callPath 的 focus=/detourSkip= 标记；达标核对
              （误判率/严重误伤）用 scripts/regression/check_e3_observation.py 人工标注后计算。
            </p>
          </section>

          <ReliabilityCharts trendBarData={trendBarData} barData={barData} />

          <div className="grid gap-4 lg:grid-cols-2">
            <section className="rounded-xl border border-line bg-surface p-4">
              <h2 className="mb-3 text-sm font-medium">模型 × 状态</h2>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-ink-faint">
                    <th className="py-1">模型</th>
                    <th className="py-1">状态分布</th>
                    <th className="py-1 text-right">合计</th>
                  </tr>
                </thead>
                <tbody>
                  {modelRows.map((r) => (
                    <tr key={r.model} className="border-t border-line">
                      <td className="py-1.5">{r.model}</td>
                      <td className="py-1.5">
                        {Object.entries(r.statuses).map(([s, n]) => `${s}:${n}`).join(' · ')}
                      </td>
                      <td className="py-1.5 text-right">{r.total}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>

            <section className="rounded-xl border border-line bg-surface p-4">
              <h2 className="mb-3 text-sm font-medium">节点执行 Top5（M9-3c 观测）</h2>
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-ink-faint">
                    <th className="py-1">节点</th>
                    <th className="py-1 text-right">次数</th>
                  </tr>
                </thead>
                <tbody>
                  {nodeRows.map((r) => (
                    <tr key={r.node} className="border-t border-line">
                      <td className="py-1.5">{r.node}</td>
                      <td className="py-1.5 text-right">{r.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </section>
          </div>

          <div className="grid gap-4 lg:grid-cols-2">
            <section className="rounded-xl border border-line bg-surface p-4">
              <h2 className="mb-3 text-sm font-medium">模型 × 耗时（M14-1c）</h2>
              {durationRows.length > 0 ? (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-ink-faint">
                      <th className="py-1">模型</th>
                      <th className="py-1 text-right">次数</th>
                      <th className="py-1 text-right">平均</th>
                      <th className="py-1 text-right">P50</th>
                      <th className="py-1 text-right">P95</th>
                      <th className="py-1 text-right">最大</th>
                    </tr>
                  </thead>
                  <tbody>
                    {durationRows.map((r) => (
                      <tr key={r.model} className="border-t border-line">
                        <td className="py-1.5">{r.model}</td>
                        <td className="py-1.5 text-right">{r.count}</td>
                        <td className="py-1.5 text-right">{fmtMs(r.avgDurationMs)}</td>
                        <td className="py-1.5 text-right">{fmtMs(r.p50DurationMs)}</td>
                        <td className="py-1.5 text-right">{fmtMs(r.p95DurationMs)}</td>
                        <td className="py-1.5 text-right">{fmtMs(r.maxDurationMs)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              ) : (
                <p className="py-6 text-center text-sm text-ink-faint">暂无耗时数据</p>
              )}
            </section>

            <section className="rounded-xl border border-line bg-surface p-4">
              <h2 className="mb-3 text-sm font-medium">高德配额水位（M14-1c）</h2>
              {quotaRows.length > 0 ? (
                quotaRows.map((r) => {
                  const dayRatio = ratio(r.dayUsed, r.dayLimit);
                  const monthRatio = ratio(r.monthUsed, r.monthLimit);
                  return (
                    <div key={r.api} className="mb-4 last:mb-0">
                      <div className="mb-1 flex items-center justify-between text-sm">
                        <span className="font-medium">{apiLabel(r.api)}</span>
                        <span className="text-ink-faint">
                          日 {r.dayUsed}/{r.dayLimit} · 月 {r.monthUsed}/{r.monthLimit}
                        </span>
                      </div>
                      <div className="space-y-1">
                        <QuotaBar label="日" ratio={dayRatio} color="bg-amber-400" />
                        <QuotaBar label="月" ratio={monthRatio} color="bg-sky-400" />
                      </div>
                    </div>
                  );
                })
              ) : (
                <p className="py-6 text-center text-sm text-ink-faint">暂无配额数据</p>
              )}
            </section>
          </div>
        </>
      )}
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-4">
      <div className="text-xs text-ink-faint">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{value}</div>
    </div>
  );
}

function fmt(v: unknown): string {
  return typeof v === 'number' ? (v * 100).toFixed(1) + '%' : '0.0%';
}

function fmtMs(v: unknown): string {
  const n = Number(v ?? 0);
  return n >= 1000 ? (n / 1000).toFixed(1) + 's' : Math.round(n) + 'ms';
}

function ratio(used: number, limit: number): number {
  return limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;
}

function apiLabel(api: string): string {
  return api === 'route' ? '路线规划' : api === 'geocode' ? '地理编码' : api;
}

function QuotaBar({ label, ratio: p, color }: { label: string; ratio: number; color: string }) {
  return (
    <div className="flex items-center gap-2 text-xs text-ink-faint">
      <span className="w-4">{label}</span>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full ${color}`} style={{ width: `${p}%` }} />
      </div>
      <span className="w-10 text-right">{p}%</span>
    </div>
  );
}
