// E-5c（20260918）：可靠性看板新增两卡（数据质量/轮次耗时）——纯展示组件，
// 数据获取走页面层 useApiQuery（复用 FE-P3.1 模式）；无图表依赖（recharts 保持
// charts.tsx 动态加载口径），shared 88.1 kB 零增幅。

import { useConfirm } from '@/components/ui/confirm-dialog';
import { formatTokenCount } from '@/lib/usage-format';

export interface EtlOutboxInfo {
  unconsumed?: number;
  total?: number;
}

export interface WritebackStreamInfo {
  key?: string;
  group?: string;
  length?: number | null;
  pending?: number | null;
  deadLetterPolicy?: string;
  error?: string;
}

export interface DataQualityPayload {
  etlOutbox?: EtlOutboxInfo;
  writebackStream?: WritebackStreamInfo;
  consistencyCheck?: string;
}

export interface TurnLatencyModelStat {
  model: string;
  count: number;
  avgMs: number;
  p50Ms: number;
  p95Ms: number;
}

export interface TurnLatencyPayload {
  windowDays?: number;
  totalTurns?: number;
  byModel?: TurnLatencyModelStat[];
  topSlow?: Record<string, unknown>[];
}

export function DataQualityCard({ payload, error, onRetry }: {
  payload: DataQualityPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  const etl = payload?.etlOutbox;
  const stream = payload?.writebackStream;
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="数据质量">
      <h2 className="mb-3 text-sm font-medium">数据质量（E-5a）</h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-lg bg-surface-2 p-3">
          <p className="text-xs text-ink-faint">ETL outbox 未消费</p>
          <p className="text-lg font-semibold">
            {etl?.unconsumed ?? '—'}
            <span className="text-xs text-ink-faint"> / {etl?.total ?? '—'}</span>
          </p>
        </div>
        <div className="rounded-lg bg-surface-2 p-3">
          <p className="text-xs text-ink-faint">writeback 待处理（PEL）</p>
          <p className="text-lg font-semibold">
            {stream?.pending ?? '—'}
            <span className="text-xs text-ink-faint"> / 长度 {stream?.length ?? '—'}</span>
          </p>
        </div>
      </div>
      <p className="mt-2 text-xs text-ink-faint">
        死信口径：{stream?.deadLetterPolicy ?? '—'}；三端对账：{payload?.consistencyCheck ?? '—'}
      </p>
    </section>
  );
}

export function TurnLatencyCard({ payload, error, onRetry }: {
  payload: TurnLatencyPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  const models = payload?.byModel ?? [];
  const topSlow = payload?.topSlow ?? [];
  return (
    <section
      className="rounded-xl border border-line bg-surface p-4"
      aria-label="轮次耗时"
    >
      <h2 className="mb-3 text-sm font-medium">
        轮次耗时（E-5b，近 {payload?.windowDays ?? 7} 天，共 {payload?.totalTurns ?? 0} 轮）
      </h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      {models.length > 0 ? (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-ink-faint">
              <th className="py-1">模型</th>
              <th className="py-1 text-right">轮次</th>
              <th className="py-1 text-right">平均</th>
              <th className="py-1 text-right">P50</th>
              <th className="py-1 text-right">P95</th>
            </tr>
          </thead>
          <tbody>
            {models.map((m) => (
              <tr key={m.model} className="border-t border-line">
                <td className="py-1.5">{m.model}</td>
                <td className="py-1.5 text-right">{m.count}</td>
                <td className="py-1.5 text-right">{fmtMs(m.avgMs)}</td>
                <td className="py-1.5 text-right">{fmtMs(m.p50Ms)}</td>
                <td className="py-1.5 text-right font-medium">{fmtMs(m.p95Ms)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="py-6 text-center text-sm text-ink-faint">暂无轮次数据</p>
      )}
      {topSlow.length > 0 && (
        <p className="mt-3 text-xs text-ink-faint">
          最慢轮次：
          {topSlow
            .map((r, i) => `#${i + 1} ${String(r.modelName ?? r.model ?? '?')} ${fmtMs(Number(r.durationMs ?? 0))}`)
            .join(' · ')}
        </p>
      )}
    </section>
  );
}

function fmtMs(v: number): string {
  return v >= 1000 ? (v / 1000).toFixed(1) + 's' : Math.round(v) + 'ms';
}


// ==================== S-B8：latency-spans 看板卡 ====================

export interface LatencySpansPayload {
  days?: number;
  samples?: number;
  ttft?: { count?: number; p50?: number; p95?: number };
  routing?: { total?: number; counts?: Record<string, number> };
  hedge?: { decided?: number; wins?: number; winRate?: number };
  stages?: Record<string, { count?: number; p50?: number; p95?: number }>;
}

export function LatencySpansCard({ payload, error, onRetry }: {
  payload: LatencySpansPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  const ttft = payload?.ttft;
  const routing = payload?.routing;
  const hedge = payload?.hedge;
  const stages = payload?.stages ?? {};
  const stageNames = Object.keys(stages);
  const fmt = (v?: number) => (v == null ? '—' : fmtMs(v));
  return (
    <section
      className="rounded-xl border border-line bg-surface p-4"
      aria-label="延迟分段"
    >
      <h2 className="mb-3 text-sm font-medium">
        延迟分段（S-B8，近 {payload?.days ?? 7} 天，共 {payload?.samples ?? 0} 样本）
      </h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-3">
        <div className="rounded-lg bg-surface-2 p-3">
          <p className="text-xs text-ink-faint">首 token P50 / P95</p>
          <p className="text-lg font-semibold">
            {fmt(ttft?.p50)} <span className="text-xs text-ink-faint">/ {fmt(ttft?.p95)}</span>
          </p>
        </div>
        <div className="rounded-lg bg-surface-2 p-3">
          <p className="text-xs text-ink-faint">对冲胜出率</p>
          <p className="text-lg font-semibold">
            {hedge?.winRate == null ? '—' : `${(hedge.winRate * 100).toFixed(1)}%`}
            <span className="text-xs text-ink-faint"> / 决策 {hedge?.decided ?? 0}</span>
          </p>
        </div>
        <div className="rounded-lg bg-surface-2 p-3">
          <p className="text-xs text-ink-faint">路由层分布</p>
          <p className="text-sm">
            {routing?.total
              ? Object.entries(routing.counts ?? {})
                  .map(([k, v]) => `${k} ${v}`)
                  .join(' · ')
              : '—'}
          </p>
        </div>
      </div>
      {stageNames.length > 0 ? (
        <table className="mt-3 w-full text-sm">
          <thead>
            <tr className="text-left text-ink-faint">
              <th className="py-1">检索段</th>
              <th className="py-1 text-right">样本</th>
              <th className="py-1 text-right">P50</th>
              <th className="py-1 text-right">P95</th>
            </tr>
          </thead>
          <tbody>
            {stageNames.map((name) => {
              const st = stages[name] ?? {};
              return (
                <tr key={name} className="border-t border-line">
                  <td className="py-1.5">{name}</td>
                  <td className="py-1.5 text-right">{st.count ?? 0}</td>
                  <td className="py-1.5 text-right">{fmt(st.p50)}</td>
                  <td className="py-1.5 text-right font-medium">{fmt(st.p95)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ) : (
        <p className="mt-3 py-4 text-center text-sm text-ink-faint">暂无分段数据</p>
      )}
    </section>
  );
}


// ==================== AY-1：既有四端点观测面补全（RK-13 / MM-6 / AL-3） ====================

export interface RagQualityPayload {
  days?: string[];
  metrics?: Record<string, number[]>;
  totals?: Record<string, number>;
}

const RAG_QUALITY_METRICS: Array<{ key: string; label: string }> = [
  { key: 'abstain', label: '弃答' },
  { key: 'lowconf', label: '低信' },
  { key: 'degraded', label: '降级' },
  { key: 'hallucflag', label: '幻觉标记' },
];

export function RagQualityCard({ payload, error, onRetry }: {
  payload: RagQualityPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="RAG 质量">
      <h2 className="mb-3 text-sm font-medium">RAG 质量（RK-13，近 7 日）</h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      <div className="grid gap-3 sm:grid-cols-4">
        {RAG_QUALITY_METRICS.map((m) => (
          <div key={m.key} className="rounded-lg bg-surface-2 p-3">
            <p className="text-xs text-ink-faint">{m.label}</p>
            <p className="text-lg font-semibold">{payload?.totals?.[m.key] ?? 0}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

export function GrayReleaseCard({ payload, error, onRetry, onToggle }: {
  payload: Record<string, boolean> | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
  /** P0-AY⑴：确认弹窗通过后由卡片回调；切换请求与业务码 toast 归页面 handler */
  onToggle?: (key: string, next: boolean) => void;
}) {
  const confirm = useConfirm();
  // AY 审计直修：快照含 overrides 元数据键（值=覆盖表对象非布尔），过滤防渲染成假灰度行
  const entries = Object.entries(payload ?? {}).filter(([k, v]) => k !== 'overrides' && typeof v === 'boolean');
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="灰度发布">
      <h2 className="mb-3 text-sm font-medium">灰度发布（MM-6）</h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      {entries.length > 0 ? (
        <div className="space-y-2">
          {entries.map(([key, on]) => {
            const next = !on;
            return (
              <div key={key} className="flex items-center justify-between gap-2 text-sm">
                <span className="truncate font-mono text-xs">{key}</span>
                <div className="flex shrink-0 items-center gap-2">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs ${
                      on ? 'bg-emerald-100 text-emerald-700' : 'bg-slate-200 text-slate-600'
                    }`}
                  >
                    {on ? '开' : '关'}
                  </span>
                  <button
                    type="button"
                    onClick={async () => {
                      const ok = await confirm({
                        title: `切换灰度键 ${key} → ${next ? '开' : '关'}？内存覆盖，重启失效`,
                        confirmText: '切换',
                      });
                      if (!ok) return;
                      onToggle?.(key, next);
                    }}
                    className="rounded-lg border border-line px-2 py-1 text-xs hover:bg-surface-2"
                  >
                    切换
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      ) : (
        <p className="py-6 text-center text-sm text-ink-faint">暂无灰度键</p>
      )}
      <p className="mt-2 text-xs text-ink-faint">
        切换为内存覆盖（重启失效）；动态写默认关闭（40501=人工闸门，提示文案原样透出）。
      </p>
    </section>
  );
}

export interface EventConsumerRow {
  channel?: string;
  key?: string;
  grayKey?: string;
  running?: boolean;
  lastConsumeAt?: string | null;
  consumeCount?: number;
  rejectCount?: number;
}

export type EventConsumersPayload = EventConsumerRow[];

export function EventConsumersCard({ payload, error, onRetry }: {
  payload: EventConsumersPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  const rows = payload ?? [];
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="事件消费者">
      <h2 className="mb-3 text-sm font-medium">事件消费者（AL-3）</h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      {rows.length > 0 ? (
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-ink-faint">
              <th className="py-1">通道</th>
              <th className="py-1">键</th>
              <th className="py-1">运行态</th>
              <th className="py-1">最近消费</th>
              <th className="py-1 text-right">消费数</th>
              <th className="py-1 text-right">拒绝数</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr
                key={`${r.channel ?? ''}:${r.key ?? ''}:${i}`}
                className="border-t border-line"
              >
                <td className="py-1.5">{r.channel ?? '—'}</td>
                <td className="py-1.5">{r.key ?? '—'}</td>
                <td className="py-1.5">{r.running ? '运行中' : '已停止'}</td>
                <td className="py-1.5">{r.lastConsumeAt || '—'}</td>
                <td className="py-1.5 text-right">{r.consumeCount ?? 0}</td>
                <td className="py-1.5 text-right">{r.rejectCount ?? 0}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <p className="py-6 text-center text-sm text-ink-faint">暂无消费者注册</p>
      )}
    </section>
  );
}

// ==================== AY-3：LLM 预算水位卡（消费 AY-2 /budget；P0-AY⑶ 零图表库） ====================

export interface BudgetPayload {
  dailyUsed?: number;
  hourlyUsed?: number;
  dailyLimit?: number;
  hourlyLimit?: number;
  enabled?: boolean;
  dayKey?: string;
  hourKey?: string;
  degraded?: boolean;
}

/** 复用配额卡 ratio 风格（page.tsx 同款口径）：宽度 min(100, used/limit*100)，>80% 红 */
function budgetRatio(used: number, limit: number): number {
  return limit > 0 ? Math.min(100, Math.round((used / limit) * 100)) : 0;
}

function BudgetBar({ label, used, limit }: { label: string; used: number; limit: number }) {
  const p = budgetRatio(used, limit);
  return (
    <div className="flex items-center gap-2 text-xs text-ink-faint">
      <span className="w-8">{label}</span>
      <div className="h-2 flex-1 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full ${p > 80 ? 'bg-red-500' : 'bg-sky-400'}`} style={{ width: `${p}%` }} />
      </div>
      <span className="whitespace-nowrap">
        {formatTokenCount(used)} / {formatTokenCount(limit)} · {p}%
      </span>
    </div>
  );
}

export function BudgetCard({ payload, error, onRetry }: {
  payload: BudgetPayload | null;
  /** AW-6：加载失败态——非空时数值区顶部红色提示+重试（页面红色提示风格族） */
  error?: string | null;
  onRetry?: () => void;
}) {
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="LLM 预算水位">
      <h2 className="mb-3 text-sm font-medium">LLM 预算水位（AY-2）</h2>
      {error ? (
        <div className="mt-1 text-xs text-red-600">
          加载失败：{error}
          {onRetry && (
            <button type="button" onClick={onRetry} className="ml-2 underline">重试</button>
          )}
        </div>
      ) : null}
      <div className="space-y-1">
        <BudgetBar label="日" used={Number(payload?.dailyUsed ?? 0)} limit={Number(payload?.dailyLimit ?? 0)} />
        <BudgetBar label="小时" used={Number(payload?.hourlyUsed ?? 0)} limit={Number(payload?.hourlyLimit ?? 0)} />
      </div>
      {payload?.degraded && (
        <p className="mt-2 text-xs text-red-600">Redis 读取失败，水位不可用（fail-open）</p>
      )}
      {payload?.enabled === false && (
        <p className="mt-2 text-xs text-ink-faint">预算护栏未启用</p>
      )}
    </section>
  );
}
