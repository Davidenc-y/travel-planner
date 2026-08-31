'use client';

import type { DayPlan } from '@/types';
import { BudgetPie } from './budget-pie';

interface BudgetSectionProps {
  estimatedCost?: number | null;
  dayPlans?: DayPlan[];
  /** 详情页为 card 底、卡片弹窗为描边底（视觉容器由调用方传入，默认统一描边块） */
  bodyClassName?: string;
}

/**
 * R3（front_design 11 §2-R3）：预算概览区块统一组件。
 * 收敛行程卡片弹窗与详情页两处逐字重复的"预算概览"块（含 estimatedCost 缺失空态，
 * C4/F2 语义保留）。bodyClassName 控制外框样式差异。
 */
export function BudgetSection({ estimatedCost, dayPlans, bodyClassName }: BudgetSectionProps) {
  return (
    <div>
      <h3 className="font-semibold mb-2">预算概览</h3>
      <div className={bodyClassName ?? 'rounded-xl border border-line p-3'}>
        {estimatedCost != null ? (
          <BudgetPie estimatedCost={estimatedCost} dayPlans={dayPlans} />
        ) : (
          <p className="py-8 text-center text-sm text-ink-faint">
            暂无费用估算（该行程生成时未产出预算数据）
          </p>
        )}
      </div>
    </div>
  );
}
