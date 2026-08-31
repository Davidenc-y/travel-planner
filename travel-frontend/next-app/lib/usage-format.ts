/**
 * U1：使用统计展示格式化（纯函数，供单测）。
 * R2：时长类格式化已迁至 lib/time-format（formatDurationMs），本文件保留 Token/热力图职责。
 */

/** Token 数中文单位：≥1亿 → 亿；≥1万 → 万（保留 1 位小数，去尾 0）；否则原值 */
export function formatTokenCount(n: number): string {
  if (!Number.isFinite(n) || n <= 0) return '0';
  if (n >= 1e8) {
    return `${trimTrailingZero(n / 1e8)}亿`;
  }
  if (n >= 1e4) {
    return `${trimTrailingZero(n / 1e4)}万`;
  }
  return String(n);
}

function trimTrailingZero(v: number): string {
  const s = (Math.round(v * 10) / 10).toFixed(1);
  return s.endsWith('.0') ? s.slice(0, -2) : s;
}

export interface HeatmapDay {
  date: string; // yyyy-MM-dd
  tokens: number;
}

export interface HeatmapColumn {
  /** 列首日期（周一） */
  start: string;
  /** 7 格（周一..周日），未来日期为 null */
  cells: (HeatmapDay | null)[];
}

/**
 * 逐日 Token → GitHub 风格热力图列（周一为一周起点，最后一列对齐到今天所在周）。
 * 与给定 daily 缺失的日期补 0；months 由组件侧从 columns 推导。
 */
export function buildHeatmapColumns(
  daily: HeatmapDay[],
  today: string
): HeatmapColumn[] {
  const byDate = new Map(daily.map((d) => [d.date, d.tokens]));
  const end = parseDate(today);
  if (!end) return [];
  // 对齐 end 所在周的周日为最后一天
  const endDow = (end.getDay() + 6) % 7; // 周一=0..周日=6
  const last = addDays(end, 6 - endDow);
  const first = addDays(last, -(53 * 7 - 1)); // 53 列
  const columns: HeatmapColumn[] = [];
  for (let colStart = first; colStart <= last; colStart = addDays(colStart, 7)) {
    const cells: (HeatmapDay | null)[] = [];
    for (let i = 0; i < 7; i += 1) {
      const d = addDays(colStart, i);
      if (d > end) {
        cells.push(null);
      } else {
        const key = fmt(d);
        cells.push({ date: key, tokens: byDate.get(key) ?? 0 });
      }
    }
    columns.push({ start: fmt(colStart), cells });
  }
  return columns;
}

/** 每周聚合：weekly[i] = 第 i 列 7 格 tokens 之和 */
export function weeklyTotals(columns: HeatmapColumn[]): number[] {
  return columns.map((c) =>
    c.cells.reduce((sum, cell) => sum + (cell?.tokens ?? 0), 0)
  );
}

/** 累计聚合：cumulative[i] = 前 i 列 tokens 之和（逐日累计，与列结构对齐由组件处理） */
export function cumulativeDaily(columns: HeatmapColumn[]): Map<string, number> {
  const out = new Map<string, number>();
  let acc = 0;
  for (const c of columns) {
    for (const cell of c.cells) {
      if (cell) {
        acc += cell.tokens;
        out.set(cell.date, acc);
      }
    }
  }
  return out;
}

function parseDate(s: string): Date | null {
  const d = new Date(`${s}T00:00:00`);
  return Number.isNaN(d.getTime()) ? null : d;
}

function addDays(d: Date, days: number): Date {
  const n = new Date(d);
  n.setDate(n.getDate() + days);
  return n;
}

function fmt(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}
