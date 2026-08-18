'use client';

import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from 'recharts';

interface BudgetPieProps {
  estimatedCost: number;
}

const COLORS = ['#6366f1', '#a78bfa', '#34d399', '#fbbf24', '#f87171', '#38bdf8'];

/** F92：预算概览环形图（recharts 动态导入，SSR=false） */
export function BudgetPie({ estimatedCost }: BudgetPieProps) {
  const data = [
    { name: '估算费用', value: estimatedCost },
  ];
  return (
    <div className="h-56 w-full">
      <ResponsiveContainer width="100%" height="100%">
        <PieChart>
          <Pie data={data} dataKey="value" nameKey="name" cx="50%" cy="50%"
               innerRadius={45} outerRadius={70} paddingAngle={2}>
            {data.map((_, i) => (
              <Cell key={i} fill={COLORS[i % COLORS.length]} />
            ))}
          </Pie>
          <Tooltip formatter={(v) => [`¥${Number(v).toLocaleString()}`, '']} />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
