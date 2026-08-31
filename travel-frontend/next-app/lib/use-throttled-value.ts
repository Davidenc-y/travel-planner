'use client';

import { useEffect, useRef, useState } from 'react';

/**
 * B3（front_design 09 C-02 守卫一）：高频更新按固定节拍转发渲染。
 * 用于流式 Markdown：token 每 24ms 进 reveal 队列，Markdown 重渲按 ~100ms 一拍执行。
 */
export function useThrottledValue<T>(value: T, intervalMs = 100): T {
  const [snapshot, setSnapshot] = useState(value);
  const lastRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const pendingRef = useRef<T>(value);

  useEffect(() => {
    pendingRef.current = value;
    const now = Date.now();
    const elapsed = now - lastRef.current;
    if (elapsed >= intervalMs) {
      lastRef.current = now;
      setSnapshot(value);
      return undefined;
    }
    if (timerRef.current !== null) return undefined;
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      lastRef.current = Date.now();
      setSnapshot(pendingRef.current);
    }, intervalMs - elapsed);
    return () => {
      if (timerRef.current !== null) {
        clearTimeout(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [value, intervalMs]);

  return snapshot;
}
