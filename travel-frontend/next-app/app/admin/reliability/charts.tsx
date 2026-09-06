'use client';

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

/**
 * M27（S6）：看板图表块（默认导出供 next/dynamic 按需加载——
 * recharts ~222KB 移出 /admin/reliability First Load JS，仅管理员访问时才拉取）。
 */
export interface ReliabilityChartsProps {
  trendBarData: { name: string; tokens: number }[];
  barData: { name: string; total: number }[];
}

export default function ReliabilityCharts({ trendBarData, barData }: ReliabilityChartsProps) {
  return (
    <>
      <section className="rounded-xl border border-line bg-surface p-4">
        <h2 className="mb-3 text-sm font-medium">Token 日趋势（成本观测）</h2>
        {trendBarData.length > 0 ? (
          <div className="h-64">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={trendBarData}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="name" />
                <YAxis allowDecimals={false} />
                <Tooltip />
                <Legend />
                <Bar dataKey="tokens" name="Token 数" fill="#10b981" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <p className="py-6 text-center text-sm text-ink-faint">
            暂无 token 数据（itinerary 图 token 采集启用后可见）
          </p>
        )}
      </section>

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
    </>
  );
}
