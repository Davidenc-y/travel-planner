'use client';

import { ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { MapPin, MessageSquare, Search, LogOut, Compass } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ThemeToggle } from './theme-toggle';
import { useAuth } from '@/lib/auth-context';

const navItems = [
  // F96：原"首页"改为"规划"（进入 /plan 规划功能页）；"/" 现为欢迎展示页（点击 Logo 返回）
  { href: '/plan', label: '规划', icon: Compass },
  { href: '/itinerary', label: '行程', icon: MapPin },
  { href: '/chat', label: '聊天', icon: MessageSquare },
  { href: '/attractions', label: '景点', icon: Search },
];

export function ClientLayout({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { isAuthenticated, username, logout } = useAuth();

  const isAuthPage = pathname === '/login' || pathname === '/register';

  if (isAuthPage) {
    return <div className="min-h-screen">{children}</div>;
  }

  return (
    <div className="min-h-screen flex flex-col">
      {/* Navbar */}
      <header className="sticky top-0 z-50 border-b border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-950/80 backdrop-blur-md">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <Link href="/" className="flex items-center gap-2 magnetic">
            <Compass className="h-6 w-6 text-brand-500" />
            <span className="text-lg font-bold">旅游规划助手</span>
          </Link>

          <nav className="hidden md:flex items-center gap-1">
            {navItems.map((item) => {
              const Icon = item.icon;
              const active = pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href));
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={cn(
                    'flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-all magnetic',
                    active
                      ? 'bg-brand-500 text-white'
                      : 'text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="flex items-center gap-2">
            {isAuthenticated ? (
              <>
                {/* F98：点击"头像占位 + 用户名"进入个人中心（MinIO 头像后续接入，先占位） */}
                <button
                  onClick={() => router.push('/profile')}
                  className="flex items-center gap-2 rounded-lg px-2 py-1 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                  aria-label="个人中心"
                >
                  <span className="flex h-8 w-8 items-center justify-center rounded-full bg-brand-500 text-white text-sm font-bold">
                    {username?.charAt(0).toUpperCase() || 'U'}
                  </span>
                  <span className="text-sm text-slate-600 dark:text-slate-300 hidden sm:inline">
                    {username}
                  </span>
                </button>
                <button
                  onClick={logout}
                  className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                  aria-label="退出"
                >
                  <LogOut className="h-5 w-5" />
                </button>
              </>
            ) : (
              <Link href="/login" className="text-sm font-medium text-brand-500 hover:underline">
                登录
              </Link>
            )}
            <ThemeToggle />
          </div>
        </div>

        {/* Mobile nav */}
        <nav className="md:hidden flex items-center justify-around border-t border-slate-200 dark:border-slate-800 px-2 py-1">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href));
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  'flex flex-col items-center gap-0.5 px-2 py-1 rounded text-xs',
                  active ? 'text-brand-500' : 'text-slate-500'
                )}
              >
                <Icon className="h-5 w-5" />
                {item.label}
              </Link>
            );
          })}
        </nav>
      </header>

      {/* Content */}
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-6 animate-fade-in">
        {children}
      </main>

      {/* Footer */}
      <footer className="border-t border-slate-200 dark:border-slate-800 py-4 text-center text-sm text-slate-400">
        旅游行程智能规划助手 © 2026 | 基于 Spring AI Alibaba
      </footer>
    </div>
  );
}
