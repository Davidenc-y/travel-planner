'use client';

import { ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { MapPin, MessageSquare, Search, LogOut, Compass } from 'lucide-react';
import { cn } from '@/lib/utils';
import { ThemeToggle } from './theme-toggle';
import { useAuth } from '@/lib/auth-context';
import { UserAvatar } from './ui/user-avatar';
import { useConfirm } from './ui/confirm-dialog';

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
  const { isAuthenticated, username, avatar, logout, mounted } = useAuth();
  const confirm = useConfirm();

  const isAuthPage = pathname === '/login' || pathname === '/register';

  // B3（04 §4.0）：登出前确认；确认后行为与原实现一致（清凭据 + 整页回首页，R2）
  const handleLogout = async () => {
    if (!(await confirm({ title: '确定退出登录？', danger: true, confirmText: '退出' }))) return;
    logout();
  };

  if (isAuthPage) {
    return <div className="min-h-screen">{children}</div>;
  }

  return (
    <div className="min-h-screen flex flex-col">
      {/* Navbar（print:hidden：M6 导出打印样式） */}
      <header className="print:hidden sticky top-0 z-50 border-b border-line glass">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <Link href="/" className="flex items-center gap-2 magnetic focus-ring rounded-lg">
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
                    'flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors duration-fast focus-ring magnetic',
                    active
                      ? 'bg-brand-500 text-white'
                      : 'text-ink-secondary hover:bg-surface-2 hover:text-ink'
                  )}
                >
                  <Icon className="h-4 w-4" />
                  {item.label}
                </Link>
              );
            })}
          </nav>

          <div className="flex items-center gap-2">
            {!mounted ? (
              // PE-02：未挂载时渲染中性占位（替代原"整站空白"），避免登录/头像区闪现
              <span aria-hidden className="h-8 w-24 rounded-lg bg-surface-2 skeleton-shimmer" />
            ) : isAuthenticated ? (
              <>
                {/* F121：点击"真实头像 + 用户名"进入个人中心 */}
                <button
                  onClick={() => router.push('/profile')}
                  className="flex items-center gap-2 rounded-lg px-2 py-1 hover:bg-surface-2 transition-colors focus-ring"
                  aria-label="个人中心"
                >
                  <UserAvatar name={username} src={avatar} size="sm" />
                  <span className="text-sm text-ink-secondary hidden sm:inline">
                    {username}
                  </span>
                </button>
                <button
                  onClick={handleLogout}
                  className="p-2 rounded-lg hover:bg-surface-2 transition-colors focus-ring"
                  aria-label="退出"
                >
                  <LogOut className="h-5 w-5" />
                </button>
              </>
            ) : (
              <Link href="/login" className="text-sm font-medium text-brand-500 hover:underline focus-ring rounded">
                登录
              </Link>
            )}
            <ThemeToggle />
          </div>
        </div>

        {/* Mobile nav */}
        <nav className="md:hidden flex items-center justify-around border-t border-line px-2 py-1">
          {navItems.map((item) => {
            const Icon = item.icon;
            const active = pathname === item.href || (item.href !== '/' && pathname.startsWith(item.href));
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  'flex flex-col items-center gap-0.5 px-2 py-1 rounded text-xs transition-colors duration-fast',
                  active ? 'text-brand-500' : 'text-ink-faint'
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

      {/* Footer（print:hidden） */}
      <footer className="print:hidden border-t border-line py-4 text-center text-sm text-ink-faint">
        旅游行程智能规划助手 © 2026 | 基于 Spring AI Alibaba
      </footer>
    </div>
  );
}
