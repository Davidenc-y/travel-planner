'use client';

import { useMemo, useState } from 'react';
import {
  CartesianGrid,
  Cell,
  Line,
  LineChart,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { userApi } from '@/lib/api';
import type { UsageStats } from '@/types';
import { useApiQuery } from '@/lib/use-api-query';
import {
  buildHeatmapColumns,
  cumulativeDaily,
  formatTokenCount,
  weeklyTotals,
  type HeatmapColumn,
} from '@/lib/usage-format';
import { formatDurationMs } from '@/lib/time-format';
import { Skeleton } from '@/components/ui/skeleton';
import { cn } from '@/lib/utils';

/**
 * U1：个人中心使用统计（参考 Z.ai 应用用量页样式）。
 * 结构：统计卡×5 → Token 活动热力图（每日/每周/累计） → 时间范围（近7/30日）
 * → 每日 Token 趋势图（按模型多线） → 模型用量环形图（中心总量 + 图例）。
 * 数据：GET /api/v1/users/me/usage-stats（t_agent_trace 聚合）。
 */

const MODEL_COLORS = ['#3b82f6', '#34d399', '#f59e0b', '#a78bfa', '#f87171', '#38bdf8'];
const HEATMAP_LEVELS = ['#dbeafe', '#93c5fd', '#60a5fa', '#2563eb']; // 依强度递进
const GAP = 2;

type HeatMode = 'daily' | 'weekly' | 'cumulative';
type RangeDays = 7 | 30;

const TODAY_STR = (() => {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
})();

/** 分段选择器（参考稿圆角胶囊样式） */
function Segmented({
  options,
  value,
  onChange,
}: {
  options: { value: string; label: string }[];
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <div className="inline-flex items-center rounded-full bg-surface-2 p-0.5">
      {options.map((opt) => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onChange(opt.value)}
          className={cn(
            'h-7 rounded-full px-3 text-xs transition-colors duration-fast focus-ring',
            value === opt.value
              ? 'bg-surface text-ink shadow-1'
              : 'text-ink-secondary hover:text-ink'
          )}
        >
          {opt.label}
        </button>
      ))}
    </div>
  );
}

/** 热力图颜色：值 → 4 档品牌蓝强度 */
function heatColor(value: number, max: number): string {
  if (max <= 0 || value <= 0) return 'var(--surface-2)';
  const ratio = value / max;
  if (ratio > 0.75) return HEATMAP_LEVELS[3];
  if (ratio > 0.5) return HEATMAP_LEVELS[2];
  if (ratio > 0.25) return HEATMAP_LEVELS[1];
  return HEATMAP_LEVELS[0];
}

export function UsageStats() {
  const [range, setRange] = useState<RangeDays>(30);
  const [heatMode, setHeatMode] = useState<HeatMode>('daily');

  // U1 数据拉取（R2/A2：统一 useApiQuery——cancelled 防护内建于 hook；range 变化自动重查）
  const { data: stats, loading, error } = useApiQuery<UsageStats>(
    () => userApi.usageStats(range).then((res) => res.data.data),
    [range]
  );

  // 热力图列（53 周，缺失日补 0）
  const columns: HeatmapColumn[] = useMemo(
    () => buildHeatmapColumns(stats?.daily ?? [], TODAY_STR),
    [stats]
  );
  const weekly = useMemo(() => weeklyTotals(columns), [columns]);
  const cumulative = useMemo(() => cumulativeDaily(columns), [columns]);

  // 各模式的"值"与上限
  const cellValue = (col: HeatmapColumn, ci: number, date: string): number => {
    if (heatMode === 'weekly') return weekly[ci] ?? 0;
    if (heatMode === 'cumulative') return cumulative.get(date) ?? 0;
    return col.cells.find((c) => c?.date === date)?.tokens ?? 0;
  };
  const heatMax = useMemo(() => {
    let max = 0;
    columns.forEach((col, ci) => {
      col.cells.forEach((cell) => {
        if (cell) max = Math.max(max, cellValue(col, ci, cell.date));
      });
    });
    return max;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [columns, weekly, cumulative, heatMode]);

  // 月份刻度（列首月份变化处标注；最小间隔 4 列防重叠——C4r1 排版修复）
  const monthLabels = useMemo(() => {
    const labels: { col: number; text: string }[] = [];
    let prevMonth = -1;
    let lastCol = -99;
    columns.forEach((col, ci) => {
      const d = new Date(`${col.start}T00:00:00`);
      const m = d.getMonth();
      if (m !== prevMonth && ci - lastCol >= 4) {
        labels.push({ col: ci, text: `${m + 1}月` });
        prevMonth = m;
        lastCol = ci;
      }
    });
    return labels;
  }, [columns]);

  // 趋势图：日期补零 + 按模型展开为列
  const trendModels = useMemo(() => {
    const order = new Map((stats?.modelUsage ?? []).map((m, i) => [m.model, i]));
    return [...new Set((stats?.trend ?? []).map((t) => t.model))].sort(
      (a, b) => (order.get(a) ?? 99) - (order.get(b) ?? 99)
    );
  }, [stats]);
  const trendRows = useMemo(() => {
    const byDateModel = new Map<string, number>();
    for (const t of stats?.trend ?? []) {
      byDateModel.set(`${t.date}|${t.model}`, t.tokens);
    }
    const rows: Record<string, number | string>[] = [];
    const end = new Date(`${TODAY_STR}T00:00:00`);
    for (let i = range - 1; i >= 0; i -= 1) {
      const d = new Date(end);
      d.setDate(d.getDate() - i);
      const p = (n: number) => String(n).padStart(2, '0');
      const key = `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
      const row: Record<string, number | string> = { date: key };
      for (const m of trendModels) {
        row[m] = byDateModel.get(`${key}|${m}`) ?? 0;
      }
      rows.push(row);
    }
    return rows;
  }, [stats, trendModels, range]);

  const modelTotal = (stats?.modelUsage ?? []).reduce((s, m) => s + m.tokens, 0);
  const colorOf = (model: string): string => {
    const i = (stats?.modelUsage ?? []).findIndex((m) => m.model === model);
    return MODEL_COLORS[(i >= 0 ? i : 0) % MODEL_COLORS.length];
  };

  const shortDate = (iso: string): string => {
    const [, m, d] = iso.split('-');
    return `${Number(m)}月${Number(d)}日`;
  };

  return (
    <div className="space-y-4">
      {/* 标题（参考稿：使用统计 + 应用用量徽标） */}
      <div className="flex items-center gap-3">
        <h2 className="text-2xl font-bold">使用统计</h2>
        <span className="inline-flex items-center rounded-full bg-surface-2 px-3 py-1 text-xs text-ink-secondary">
          应用用量
        </span>
      </div>

      {/* 统计卡 ×5（单行时 divide-x 分隔，参考稿样式） */}
      <div className="card grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-y-4 px-2 py-4 lg:divide-x divide-line">
        {[
          { label: '累计 Token 数', value: stats ? formatTokenCount(stats.totalTokens) : null },
          { label: '峰值 Token 数', value: stats ? formatTokenCount(stats.peakDayTokens) : null },
          { label: '最长聊天时长', value: stats ? formatDurationMs(stats.longestTurnMs) : null },
          { label: '当前连续天数', value: stats ? `${stats.currentStreakDays} 天` : null },
          { label: '最长连续天数', value: stats ? `${stats.longestStreakDays} 天` : null },
        ].map((item) => (
          <div key={item.label} className="px-2 text-center">
            <p className="text-lg font-semibold" title={item.value ?? ''}>
              {loading ? <Skeleton className="mx-auto h-6 w-16" /> : item.value}
            </p>
            <p className="mt-0.5 text-xs text-ink-faint">{item.label}</p>
          </div>
        ))}
      </div>

      {/* Token 活动（热力图 + 每日/每周/累计） */}
      <div className="card p-4">
        <div className="mb-3 flex items-center justify-between">
          <h3 className="font-semibold">Token 活动</h3>
          <Segmented
            options={[
              { value: 'daily', label: '每日' },
              { value: 'weekly', label: '每周' },
              { value: 'cumulative', label: '累计' },
            ]}
            value={heatMode}
            onChange={(v) => setHeatMode(v as HeatMode)}
          />
        </div>
        {loading ? (
          <Skeleton className="h-24 w-full" />
        ) : error ? (
          <p className="py-6 text-center text-sm text-ink-faint">{error}</p>
        ) : (
          /* C4r1 排版修复：流式网格——53 列均分卡片宽度（不再固定 10px 导致左偏留白），
             格子 aspect-square 自适应；月份刻度用同结构网格对齐（最小间距 4 列防重叠） */
          <div className="w-full">
            <div
              className="grid w-full"
              style={{
                gridTemplateRows: 'repeat(7, minmax(0, 1fr))',
                gridTemplateColumns: `repeat(${columns.length}, minmax(0, 1fr))`,
                gridAutoFlow: 'column',
                gap: GAP,
              }}
            >
              {columns.map((col, ci) =>
                col.cells.map((cell, ri) => {
                  if (!cell) {
                    return <div key={`${ci}-${ri}`} className="aspect-square w-full opacity-0" />;
                  }
                  const v = cellValue(col, ci, cell.date);
                  return (
                    <div
                      key={`${ci}-${ri}`}
                      title={`${cell.date}：${formatTokenCount(v)} tokens`}
                      style={{ backgroundColor: heatColor(v, heatMax) }}
                      className="aspect-square w-full rounded-[2px] transition-colors duration-base"
                    />
                  );
                })
              )}
            </div>
            {/* 月份刻度（底部，与列网格同构对齐） */}
            <div className="mt-1 h-4 text-[10px] text-ink-faint">
              <div
                className="grid w-full"
                style={{
                  gridTemplateColumns: `repeat(${columns.length}, minmax(0, 1fr))`,
                  gap: GAP,
                }}
              >
                {monthLabels.map((l) => (
                  <span
                    key={`${l.col}-${l.text}`}
                    className="whitespace-nowrap"
                    style={{ gridColumnStart: l.col + 1 }}
                  >
                    {l.text}
                  </span>
                ))}
              </div>
            </div>
          </div>
        )}
      </div>

      {/* 时间范围 */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium text-ink-secondary">时间范围</h3>
        <Segmented
          options={[
            { value: '7', label: '近 7 日' },
            { value: '30', label: '近 30 日' },
          ]}
          value={String(range)}
          onChange={(v) => setRange(Number(v) as RangeDays)}
        />
      </div>

      {/* 每日 Token 趋势图（按模型多线，参考稿样式） */}
      <div className="card p-4">
        <h3 className="mb-3 font-semibold">每日 Token 趋势图</h3>
        {!loading && trendModels.length > 0 && (
          <div className="mb-2 flex flex-wrap items-center gap-4">
            {trendModels.map((m) => (
              <span key={m} className="inline-flex items-center gap-1.5 text-xs text-ink-secondary">
                <span
                  aria-hidden
                  className="inline-block h-2.5 w-2.5 rounded-full"
                  style={{ backgroundColor: colorOf(m) }}
                />
                {m}
              </span>
            ))}
          </div>
        )}
        {loading ? (
          <Skeleton className="h-60 w-full" />
        ) : error ? (
          <p className="py-10 text-center text-sm text-ink-faint">{error}</p>
        ) : trendModels.length === 0 ? (
          <p className="py-10 text-center text-sm text-ink-faint">所选范围内暂无使用数据</p>
        ) : (
          <div className="h-60 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart data={trendRows} margin={{ top: 4, right: 8, bottom: 0, left: 8 }}>
                <CartesianGrid vertical={false} strokeDasharray="3 3" stroke="var(--line)" />
                <XAxis
                  dataKey="date"
                  tickFormatter={shortDate}
                  tick={{ fontSize: 11, fill: 'var(--ink-faint)' }}
                  axisLine={{ stroke: 'var(--line)' }}
                  tickLine={false}
                  minTickGap={24}
                />
                <YAxis hide />
                <Tooltip
                  labelFormatter={(l) => shortDate(String(l))}
                  formatter={(v: number | string, name: string) => [`${formatTokenCount(Number(v))} tokens`, name]}
                  contentStyle={{
                    background: 'var(--surface-1)',
                    border: '1px solid var(--line)',
                    borderRadius: 8,
                    fontSize: 12,
                    color: 'var(--ink)',
                  }}
                />
                {trendModels.map((m) => (
                  <Line
                    key={m}
                    type="monotone"
                    dataKey={m}
                    stroke={colorOf(m)}
                    strokeWidth={2}
                    dot={false}
                    connectNulls
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      {/* 模型用量（环形图 + 图例，参考稿样式） */}
      <div className="card p-4">
        <h3 className="mb-3 font-semibold">模型用量</h3>
        {loading ? (
          <Skeleton className="h-56 w-full" />
        ) : error ? (
          <p className="py-10 text-center text-sm text-ink-faint">{error}</p>
        ) : modelTotal <= 0 ? (
          <p className="py-10 text-center text-sm text-ink-faint">所选范围内暂无使用数据</p>
        ) : (
          <div className="flex flex-col items-center gap-6 sm:flex-row">
            <div className="relative h-52 w-52 flex-shrink-0">
              <ResponsiveContainer width="100%" height="100%">
                <PieChart>
                  <Pie
                    data={stats?.modelUsage ?? []}
                    dataKey="tokens"
                    nameKey="model"
                    cx="50%"
                    cy="50%"
                    innerRadius="62%"
                    outerRadius="88%"
                    paddingAngle={2}
                    stroke="none"
                  >
                    {(stats?.modelUsage ?? []).map((m) => (
                      <Cell key={m.model} fill={colorOf(m.model)} />
                    ))}
                  </Pie>
                </PieChart>
              </ResponsiveContainer>
              <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
                <p className="text-lg font-semibold">{formatTokenCount(modelTotal)}</p>
                <p className="text-xs text-ink-faint">tokens</p>
              </div>
            </div>
            <div className="w-full flex-1">
              {(stats?.modelUsage ?? []).map((m, i) => (
                <div
                  key={m.model}
                  className={cn(
                    'flex items-center gap-2 py-2.5 text-sm',
                    i > 0 && 'border-t border-line'
                  )}
                >
                  <span
                    aria-hidden
                    className="inline-block h-2.5 w-2.5 flex-shrink-0 rounded-full"
                    style={{ backgroundColor: colorOf(m.model) }}
                  />
                  <div className="min-w-0 flex-1">
                    <p className="truncate font-medium">{m.model}</p>
                    <p className="text-xs text-ink-faint">
                      {formatTokenCount(m.tokens)} tokens
                    </p>
                  </div>
                  <span className="text-xs text-ink-secondary">
                    {Math.round((m.tokens / modelTotal) * 100)}%
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
