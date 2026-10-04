import type { R } from '@/types';
import { planningApi } from './http';

// ==================== Admin（M11-3：可靠性看板） ====================
export const adminApi = {
  /** 近 N 天可靠性聚合（后端按 travel.admin.user-ids 白名单门控） */
  reliabilityStats: (days = 7) =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/stats', {
      params: { days },
    }),
  /** E-5a：数据质量聚合（outbox 未消费/writeback PEL/对账标记） */
  dataQuality: () =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/data-quality'),
  /** E-5b：慢轮次聚合（按模型 P50/P95/avg/count + Top 慢轮次） */
  turnLatency: (days = 7) =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/turn-latency', {
      params: { days },
    }),
  /** S-B8：latency-spans 聚合（ttft/routing 分布/hedge 胜率/检索五段 P95；§八⑤授权端点） */
  latencySpans: (days = 7) =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/latency-spans', {
      params: { days },
    }),
  /** RK-13/D-5：RAG 线上质量（近 7 日 abstain/lowconf/degraded/hallucflag 日计数+合计） */
  ragQuality: () =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/rag-quality'),
  /** MM-6：灰度开关快照（全部已知键 → 当前布尔值；只读） */
  graySnapshot: () =>
    planningApi.get<R<Record<string, boolean>>>('/api/v1/admin/reliability/gray-release/snapshot'),
  /** MM-8：灰度动态切换（默认 40501=动态写未启用；成功返回新快照） */
  grayOverride: (key: string, on: boolean) =>
    planningApi.put<R<Record<string, boolean>>>(
      `/api/v1/admin/reliability/gray-release/${encodeURIComponent(key)}`,
      null,
      { params: { on } },
    ),
  /** AL-3：事件消费者注册中心观测（channel/key/grayKey/running/lastConsumeAt/计数） */
  eventConsumers: () =>
    planningApi.get<R<unknown[]>>('/api/v1/admin/reliability/event-consumers'),
  /** AY-2：LLM 预算水位（只读；日/小时已用+限额+degraded 标记） */
  budget: () =>
    planningApi.get<R<Record<string, unknown>>>('/api/v1/admin/reliability/budget'),
};
