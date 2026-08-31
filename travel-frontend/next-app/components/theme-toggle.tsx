'use client';

import { useEffect, useState } from 'react';
import { useTheme } from 'next-themes';
import { Moon, Sun } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * F94 横向滑动开关 + B1 修复（F-05）：
 * 用 resolvedTheme 判断当前生效主题（defaultTheme="system" 下 theme 恒为 'system'，
 * 原实现系统深色时图标状态错误、首次点击方向反直觉）。
 * 挂载前渲染中性占位，避免 SSR/水合不一致。
 */
export function ThemeToggle() {
  const { resolvedTheme, setTheme } = useTheme();
  const [mounted, setMounted] = useState(false);

  useEffect(() => setMounted(true), []);

  if (!mounted) {
    return (
      <span
        aria-hidden
        className="relative h-7 w-12 shrink-0 rounded-full bg-slate-200 dark:bg-slate-700"
      />
    );
  }

  const isDark = resolvedTheme === 'dark';

  return (
    <button
      onClick={() => setTheme(isDark ? 'light' : 'dark')}
      className={cn(
        // F94：横向滑动开关——高度减小(h-7)、左右变宽(w-12)，图案左右移动不越界
        'relative h-7 w-12 shrink-0 rounded-full transition-colors duration-200 magnetic focus-ring',
        'bg-slate-200 dark:bg-slate-700'
      )}
      aria-label="切换主题"
    >
      <span
        className={cn(
          'absolute top-0.5 left-0.5 flex h-6 w-6 items-center justify-center rounded-full',
          'bg-white dark:bg-slate-950 shadow transition-transform duration-200',
          isDark ? 'translate-x-5' : 'translate-x-0'
        )}
      >
        {isDark ? <Moon className="h-3.5 w-3.5" /> : <Sun className="h-3.5 w-3.5" />}
      </span>
    </button>
  );
}
