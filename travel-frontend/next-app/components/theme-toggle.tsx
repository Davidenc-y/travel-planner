'use client';

import { useTheme } from 'next-themes';
import { Moon, Sun } from 'lucide-react';
import { cn } from '@/lib/utils';

export function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const isDark = theme === 'dark';

  return (
    <button
      onClick={() => setTheme(theme === 'dark' ? 'light' : 'dark')}
      className={cn(
        // F94：横向滑动开关——高度减小(h-7)、左右变宽(w-12)，图案左右移动不越界
        'relative h-7 w-12 shrink-0 rounded-full transition-colors duration-200 magnetic',
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
