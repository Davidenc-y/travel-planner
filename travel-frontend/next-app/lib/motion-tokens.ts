'use client';

/**
 * FE-A1/A3（20260912 前端专项）：动画时长 token（TS 侧单一事实源；
 * globals.css :root 的 --dur-* 变量与之同值镜像）+ 最少展示 hook。
 */
import { useEffect, useRef, useState } from 'react';

export const DUR_MS = {
  fast: 150,
  base: 250,
  slow: 400,
  reveal: 300,
} as const;

/**
 * 骨架最少展示时长（防闪跳）：骨架已展示 elapsedMs，若不足 minMs 则返回还需
 * 延迟的毫秒数（调用方延迟切换到内容）；已满足返回 0。
 */
export function minDisplay(elapsedMs: number, minMs: number = DUR_MS.reveal): number {
  const elapsed = elapsedMs < 0 ? 0 : elapsedMs;
  return Math.max(0, minMs - elapsed);
}

/**
 * FE-A3：最少展示 hook——active 期间记录起始时刻；active 翻 false 时若持续时长
 * 不足 minMs 则返回 true 保持展示态至补足（调用方用它维持指示器渲染防闪现即逝）。
 * 供 ListState 骨架保持与 message-stream-wrapper thinking 指示器复用。
 */
export function useMinDisplay(active: boolean, minMs: number = DUR_MS.slow): boolean {
  const sinceRef = useRef<number | null>(null);
  const [holding, setHolding] = useState(false);

  useEffect(() => {
    if (active) {
      sinceRef.current = Date.now();
      setHolding(false);
      return undefined;
    }
    const since = sinceRef.current;
    if (since == null) return undefined;
    const remain = minDisplay(Date.now() - since, minMs);
    if (remain <= 0) {
      sinceRef.current = null;
      return undefined;
    }
    setHolding(true);
    const timer = setTimeout(() => {
      setHolding(false);
      sinceRef.current = null;
    }, remain);
    return () => clearTimeout(timer);
  }, [active, minMs]);

  return holding;
}
