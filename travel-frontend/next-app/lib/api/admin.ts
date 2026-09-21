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
};
