// E-5c（20260918）：可靠性看板新增两卡（数据质量/轮次耗时）——纯展示组件，
// 数据获取走页面层 useApiQuery（复用 FE-P3.1 模式）；无图表依赖（recharts 保持
// charts.tsx 动态加载口径），shared 88.1 kB 零增幅。

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

export function DataQualityCard({ payload }: { payload: DataQualityPayload | null }) {
  const etl = payload?.etlOutbox;
  const stream = payload?.writebackStream;
  return (
    <section className="rounded-xl border border-line bg-surface p-4" aria-label="数据质量">
      <h2 className="mb-3 text-sm font-medium">数据质量（E-5a）</h2>
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

export function TurnLatencyCard({ payload }: { payload: TurnLatencyPayload | null }) {
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
