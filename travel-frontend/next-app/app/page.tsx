'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Sparkles, ArrowRight, Compass } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * 首页（F96）：纯展示性欢迎页，渐进式展示欢迎语句；点击"开始规划"进入 /plan 功能页。
 */
const WELCOME_LINES = [
  '你好，欢迎来到旅游行程智能规划助手。',
  '我可以根据你的目的地、天数、预算与兴趣，为你生成个性化行程。',
  '你的偏好会被记录，跨会话也能被记住。',
  '点击下方按钮，开始你的第一次智能规划。',
];

function WelcomePage() {
  const router = useRouter();
  const [visible, setVisible] = useState(0);

  // 渐进式逐行展示（每 900ms 显示一行）
  useEffect(() => {
    if (visible >= WELCOME_LINES.length) return;
    const timer = setTimeout(() => setVisible((v) => v + 1), 900);
    return () => clearTimeout(timer);
  }, [visible]);

  return (
    <div className="min-h-[70vh] flex flex-col items-center justify-center text-center px-4">
      <div className="inline-flex items-center gap-2 px-4 py-1.5 rounded-full bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300 text-sm font-medium mb-8 animate-fade-in">
        <Compass className="h-4 w-4" />
        旅游行程智能规划助手
      </div>

      <div className="space-y-3 mb-10 max-w-2xl">
        {WELCOME_LINES.map((line, idx) => (
          <p
            key={idx}
            className={cn(
              'text-lg md:text-xl leading-relaxed transition-all duration-700',
              idx < visible ? 'opacity-100 translate-y-0' : 'opacity-0 translate-y-3'
            )}
          >
            {line}
          </p>
        ))}
      </div>

      <button
        onClick={() => router.push('/plan')}
        className={cn(
          'flex items-center gap-2 px-8 py-3 rounded-2xl bg-brand-500 text-white font-medium',
          'hover:bg-brand-600 transition-all magnetic shadow-lg shadow-brand-500/20',
          visible < WELCOME_LINES.length ? 'opacity-0 pointer-events-none' : 'opacity-100'
        )}
      >
        <Sparkles className="h-5 w-5" />
        开始规划
        <ArrowRight className="h-5 w-5" />
      </button>
    </div>
  );
}

export default WelcomePage;
