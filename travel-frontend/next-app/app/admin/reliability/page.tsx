'use client';

import { useEffect, useState } from 'react';
import {
  Bar,
  BarChart,
  CartesianGrid,
  Legend,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { adminApi, getErrorMessage } from '@/lib/api';
import { useAuth } from '@/lib/auth-context';

interface ModelRow {
  model: string;
  statuses: Record<string, number>;
  total: number;
}

interface NodeRow {
  node: string;
  count: number;
}

/**
 * M11-3：可靠性看板（后端白名单门控，非管理员后端返回 40302）。
 */
export default function ReliabilityPage() {
  const { isAuthenticated, mounted } = useAuth();
  const [days, setDays] = useState(7);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [stats, setStats] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (!mounted || !isAuthenticated) return;
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
  }, [days, isAuthenticated, mounted]);

  const modelRows = (stats?.modelDistribution as ModelRow[] | undefined) ?? [];
  const nodeRows = (stats?.topNodes as NodeRow[] | undefined) ?? [];
  const barData = modelRows.map((r) => ({
    name: r.model,
    total: r.total,
  }));

  return (
    <div className="mx-auto max-w-6xl px-4 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">可靠性看板</h1>
          <p className="text-sm text-ink-faint">
            基于 t_agent_trace 的 grounding / retention / degraded / 模型分布聚合
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
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Metric label="Trace 总数" value={String(stats.traceTotal ?? 0)} />
            <Metric label="Grounding 均值" value={fmt(stats.groundingRate)} />
            <Metric label="Retention 均值" value={fmt(stats.retentionRate)} />
            <Metric label="Degraded 次数" value={String(stats.degraded ?? 0)} />
          </div>

          <section className="rounded-xl border border-line bg-surface p-4">
            <h2 className="mb-3 text-sm font-medium">模型调用分布（Top）</h2>
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={barData}>
                  <CartesianGrid strokeDasharray="3 3" />
                  <XAxis dataKey="name" />
                  <YAxis allowDecimals={false} />
                  <Tooltip />
                  <Legend />
                  <Bar dataKey="total" name="调用次数" fill="#6366f1" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          </section>

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
