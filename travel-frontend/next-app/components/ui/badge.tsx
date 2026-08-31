import type { HTMLAttributes } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/**
 * B1（front_design 02 §5.3）：状态徽标。
 * 迁移对象：行程状态徽标（amber/red 裸写）、景点类型标签、检索 source·score 标签。
 * soft 底色 + 语义前景，深浅色由 CSS 变量切换。
 */
const badgeVariants = cva(
  'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium',
  {
    variants: {
      tone: {
        neutral: 'bg-surface-2 text-ink-secondary',
        brand: 'bg-brand-50 text-brand-600 dark:bg-brand-900/30 dark:text-brand-300',
        success: 'bg-success-soft text-success',
        warning: 'bg-warning-soft text-warning',
        danger: 'bg-danger-soft text-danger',
      },
    },
    defaultVariants: { tone: 'neutral' },
  }
);

export interface BadgeProps
  extends HTMLAttributes<HTMLSpanElement>,
    VariantProps<typeof badgeVariants> {}

export function Badge({ className, tone, ...props }: BadgeProps) {
  return <span className={cn(badgeVariants({ tone }), className)} {...props} />;
}
