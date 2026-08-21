'use client';

import type { ReactNode } from 'react';
import { AlertCircle } from 'lucide-react';

interface FormShellProps {
  title?: string;
  description?: string;
  icon?: ReactNode;
  error?: string | null;
  children: ReactNode;
  footer?: ReactNode;
}

/**
 * M3-21：表单卡片统一外壳（图标/标题/描述 + 错误横幅 + 可选 footer），
 * 消除登录/注册/规划等页面的表单卡片样板（P2-15）。
 */
export function FormShell({ title, description, icon, error, children, footer }: FormShellProps) {
  return (
    <div className="glass rounded-2xl p-6 sm:p-8 animate-slide-up">
      {(icon || title) && (
        <div className="text-center mb-6">
          {icon && (
            <div className="inline-flex items-center justify-center w-14 h-14 rounded-2xl bg-brand-500 text-white mb-3">
              {icon}
            </div>
          )}
          {title && <h1 className="text-2xl font-bold">{title}</h1>}
          {description && <p className="text-sm text-slate-400 mt-1">{description}</p>}
        </div>
      )}
      {error && (
        <div className="mb-4 flex items-center gap-2 rounded-lg bg-red-50 dark:bg-red-900/20 px-3 py-2 text-sm text-red-600 dark:text-red-400">
          <AlertCircle className="h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {children}
      {footer && <div className="mt-6">{footer}</div>}
    </div>
  );
}
