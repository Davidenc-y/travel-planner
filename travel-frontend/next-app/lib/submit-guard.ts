/**
 * FE-S3（20260912 前端专项）：写操作双击防重（客户端幂等护栏）。
 * guarded(key, fn)：同键在途返回同一 Promise（合并等待）；完成即释放（成功/失败均清键，
 * 失败后可立即重试）；不同键相互隔离。不生成服务端幂等键——服务端 Idempotency-Key
 * 支持列为远期可选项（需后端配合，违反本轮约束）。
 */
const inflight = new Map<string, Promise<unknown>>();

export function guarded<T>(key: string, fn: () => Promise<T>): Promise<T> {
  const existing = inflight.get(key) as Promise<T> | undefined;
  if (existing) return existing;
  const p = fn().finally(() => {
    if (inflight.get(key) === p) inflight.delete(key);
  });
  inflight.set(key, p);
  return p;
}

/** 测试/编排用：清空在途表（生产代码不得调用） */
export function resetSubmitGuardForTests(): void {
  inflight.clear();
}
