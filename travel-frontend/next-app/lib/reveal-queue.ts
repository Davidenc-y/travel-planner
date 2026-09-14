/**
 * FE-A2a（20260912 前端专项）：思考过程逐行揭示节流队列（展示层节流——
 * **不修改 useChatStream 的采集逻辑**，store.thinking / thinkingLines 契约不变）。
 *
 * <p>enqueue(line) 入队；tick（默认 300ms）释放一行给 onLine；flush() 立即
 * 释放全部滞留行并停表（流结束/停止时调用，防丢行）；pending() 只读在途数。
 * 上限保护：超过 maxPending 丢弃最旧行（思考日志为滚动流，保最新）。</p>
 */
export interface RevealQueueOptions {
  /** 释放间隔，默认 300ms（--dur-reveal 同源节奏） */
  tickMs?: number;
  /** 滞留行上限，默认 200（超出丢最旧） */
  maxPending?: number;
}

export class RevealQueue {
  private readonly onLine: (line: string) => void;
  private readonly tickMs: number;
  private readonly maxPending: number;
  private lines: string[] = [];
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor(onLine: (line: string) => void, options?: RevealQueueOptions) {
    this.onLine = onLine;
    this.tickMs = options?.tickMs ?? 300;
    this.maxPending = options?.maxPending ?? 200;
  }

  enqueue(line: string): void {
    if (this.lines.length >= this.maxPending) {
      this.lines.shift(); // 上限保护：丢最旧，队列不无界增长
    }
    this.lines.push(line);
    if (this.timer == null) {
      this.timer = setInterval(() => this.releaseOne(), this.tickMs);
    }
  }

  /** 立即释放全部滞留行并停表（流结束/停止防丢行） */
  flush(): void {
    const remaining = this.lines.splice(0);
    this.stopTimer();
    for (const line of remaining) {
      this.emit(line);
    }
  }

  /** 只读：滞留行数 */
  pending(): number {
    return this.lines.length;
  }

  /** 停表并清空（组件卸载用；不触发 onLine） */
  dispose(): void {
    this.stopTimer();
    this.lines = [];
  }

  private releaseOne(): void {
    const line = this.lines.shift();
    if (line === undefined) {
      this.stopTimer();
      return;
    }
    this.emit(line);
    if (this.lines.length === 0) this.stopTimer();
  }

  private emit(line: string): void {
    try {
      this.onLine(line);
    } catch {
      // 展示层回调异常不阻断队列（与预取 .catch(() => {}) 同口径）
    }
  }

  private stopTimer(): void {
    if (this.timer != null) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }
}
