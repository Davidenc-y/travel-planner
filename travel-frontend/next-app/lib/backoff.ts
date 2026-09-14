/**
 * FE-C2（20260912 前端专项）：重试退避纯函数（指数 + 抖动，node 可测）。
 * 节奏：attempt 0 → 0ms（首次立即重试）；attempt n≥1 → baseMs×factor^(n-1) ± n×jitterMs，
 * 结果钳位到 [0, capMs]。随机源可注入（测试确定性；生产用 Math.random）。
 */
export interface BackoffOptions {
  /** 退避基数，默认 300ms */
  baseMs?: number;
  /** 增长因子，默认 3（300 → 900 → 2700…） */
  factor?: number;
  /** 单次延迟上限，默认 10_000ms */
  capMs?: number;
  /** 每档抖动幅度（±attempt×jitterMs），默认 100ms */
  jitterMs?: number;
  /** [0,1) 随机源，默认 Math.random */
  random?: () => number;
}

export function backoffDelay(attempt: number, options?: BackoffOptions): number {
  const baseMs = options?.baseMs ?? 300;
  const factor = options?.factor ?? 3;
  const capMs = options?.capMs ?? 10_000;
  const jitterMs = options?.jitterMs ?? 100;
  const random = options?.random ?? Math.random;

  if (attempt <= 0) return 0;
  const raw = baseMs * Math.pow(factor, attempt - 1);
  const amp = attempt * jitterMs;
  const delay = raw + (random() * 2 - 1) * amp;
  return Math.max(0, Math.min(capMs, Math.round(delay)));
}
