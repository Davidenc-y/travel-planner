'use client';

import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip, Legend } from 'recharts';
import type { DayPlan } from '@/types';
import { formatTokenCount } from '@/lib/usage-format';

interface BudgetPieProps {
  estimatedCost: number;
  /** B4/M2 方案 A：行程景点门票费用聚合（可选，无数据时退化为整体估算展示） */
  dayPlans?: DayPlan[];
}

// C4/F2-3：首色与全站 brand 一致（原 #6366f1）
const COLORS = ['#3b82f6', '#a78bfa', '#34d399', '#fbbf24', '#f87171', '#38bdf8'];

/**
 * B4（front_design 05 M2 方案 A）+ C4/F2：预算构成环形图。
 * 数据源：dayPlans[].attractions[].cost（后端字段已存在，多为可选）；
 * 有门票数据时拆分为「景点门票 / 其他（估算）」，否则退化为整体估算单环；
 * C4/F2：单扇区退化时中心叠加总费用（消除"一色环=渲染错误"的误读），口径标注"估算"。
 */
export function BudgetPie({ estimatedCost, dayPlans }: BudgetPieProps) {
  const ticketCost = (dayPlans ?? [])
    .flatMap((day) => day.attractions ?? [])
    .reduce((sum, attr) => {
      const cost = Number(attr.cost);
      return Number.isFinite(cost) && cost > 0 ? sum + cost : sum;
    }, 0);

  const hasTickets = ticketCost > 0;
  const rest = Math.max(0, estimatedCost - ticketCost);
  const data = hasTickets
    ? [
        { name: `景点门票 ¥${ticketCost.toLocaleString()}`, value: ticketCost },
        { name: `其他（估算） ¥${rest.toLocaleString()}`, value: rest },
      ].filter((d) => d.value > 0)
    : [{ name: `估算费用 ¥${estimatedCost.toLocaleString()}`, value: estimatedCost }];

  return (
    <div className="h-56 w-full">
      <div className="relative h-full w-full">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="name" cx="50%" cy="50%"
                 innerRadius="62%" outerRadius="88%" paddingAngle={2}
                 stroke="none">
              {data.map((_, i) => (
                <Cell key={i} fill={COLORS[i % COLORS.length]} />
              ))}
            </Pie>
            <Tooltip formatter={(v) => [`¥${Number(v).toLocaleString()}`, '']} />
            <Legend verticalAlign="bottom" height={24} formatter={(v) => <span className="text-xs">{v}</span>} />
          </PieChart>
        </ResponsiveContainer>
        {/* C4/F2：中心总费用（单扇区/多扇区均展示） */}
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <p className="text-lg font-semibold">¥{formatTokenCount(estimatedCost)}</p>
          <p className="text-xs text-ink-faint">估算费用</p>
        </div>
      </div>
      {hasTickets && (
        <p className="text-center text-[10px] text-ink-faint">
          总估算 ¥{estimatedCost.toLocaleString()} · 门票基于行程景点报价，其余为估算
        </p>
      )}
    </div>
  );
}
