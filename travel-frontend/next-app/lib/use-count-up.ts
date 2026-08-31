'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * B2（front_design 03 §4.5）：数字滚动（profile 行程数、详情费用）。
 * rAF 实现，200ms ease-out；reduced-motion 直接显示终值（R7）。
 */
export function useCountUp(target: number | null | undefined, durationMs = 200): string {
  const [display, setDisplay] = useState(0);
  const fromRef = useRef(0);

  useEffect(() => {
    if (target == null || Number.isNaN(target)) {
      setDisplay(0);
      return undefined;
    }
    const reduced =
      typeof window !== 'undefined'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced || durationMs <= 0) {
      fromRef.current = target;
      setDisplay(target);
      return undefined;
    }
    const from = fromRef.current;
    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - start) / durationMs);
      const eased = 1 - Math.pow(1 - t, 3);
      const value = Math.round(from + (target - from) * eased);
      setDisplay(value);
      if (t < 1) {
        raf = requestAnimationFrame(tick);
      } else {
        fromRef.current = target;
      }
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [target, durationMs]);

  return String(display);
}
