'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { Sparkles, ArrowRight, Compass, Map, MessagesSquare, Search, ClipboardList } from 'lucide-react';
import { cn } from '@/lib/utils';
import { useAuth } from '@/lib/auth-context';

/**
 * 首页（F96）+ B3 改造（04 §4.1，D-10：欢迎页+能力卡）：
 * 1) CTA 提前至首行文案出现后即现（原 3.6s 全部文案结束才出现，F-22）；
 * 2) reduced-motion 用户文案与 CTA 直接全显（R7）；
 * 3) 新增四张能力卡直达各功能页；已登录显示个性化问候。
 */
const WELCOME_LINES = [
  '你好，欢迎来到旅游行程智能规划助手。',
  '我可以根据你的目的地、天数、预算与兴趣，为你生成个性化行程。',
  '你的偏好会被记录，跨会话也能被记住。',
];

const CAPABILITIES = [
  { href: '/plan', icon: Map, title: '表单规划', desc: '输入偏好，AI 生成每日行程' },
  { href: '/chat', icon: MessagesSquare, title: '对话规划', desc: '多轮对话，边聊边完善计划' },
  { href: '/attractions', icon: Search, title: '景点发现', desc: '语义检索与城市浏览' },
  { href: '/itinerary', icon: ClipboardList, title: '我的行程', desc: '管理、续跑与导出行程' },
];

function WelcomePage() {
  const router = useRouter();
  const { username, isAuthenticated, mounted } = useAuth();
  const [visible, setVisible] = useState(0);

  useEffect(() => {
    const reduced = typeof window !== 'undefined'
      && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduced) {
      // R7：减少动效用户直接全显（04 §4.1）
      setVisible(WELCOME_LINES.length);
      return undefined;
    }
    if (visible >= WELCOME_LINES.length) return undefined;
    // F-22：节奏 900ms → 600ms，且 CTA 不再等待全部文案
    const timer = setTimeout(() => setVisible((v) => v + 1), 600);
    return () => clearTimeout(timer);
  }, [visible]);

  const ctaVisible = visible >= 1; // 首行出现后 CTA 即可见

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
            {idx === 0 && mounted && isAuthenticated && username
              ? `欢迎回来，${username}。`
              : line}
          </p>
        ))}
      </div>

      <button
        onClick={() => router.push('/plan')}
        className={cn(
          'flex items-center gap-2 px-8 py-3 rounded-2xl bg-brand-500 text-white font-medium',
          'hover:bg-brand-600 transition-all magnetic shadow-lg shadow-brand-500/20 focus-ring',
          ctaVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'
        )}
      >
        <Sparkles className="h-5 w-5" />
        开始规划
        <ArrowRight className="h-5 w-5" />
      </button>

      {/* 能力卡（D-10）：纯静态入口，无数据依赖 */}
      <div className="mt-14 grid gap-4 sm:grid-cols-2 lg:grid-cols-4 max-w-4xl w-full">
        {CAPABILITIES.map((cap, idx) => {
          const Icon = cap.icon;
          return (
            <button
              key={cap.href}
              type="button"
              onClick={() => router.push(cap.href)}
              style={{ animationDelay: `${idx * 40}ms` }}
              className="card p-5 text-left transition-all duration-base hover:shadow-2 hover:border-brand-300 magnetic animate-rise focus-ring"
            >
              <span className="inline-flex h-10 w-10 items-center justify-center rounded-xl bg-brand-50 dark:bg-brand-900/30 text-brand-500 mb-3">
                <Icon className="h-5 w-5" />
              </span>
              <p className="font-semibold">{cap.title}</p>
              <p className="mt-0.5 text-sm text-ink-secondary">{cap.desc}</p>
            </button>
          );
        })}
      </div>
    </div>
  );
}

export default WelcomePage;
