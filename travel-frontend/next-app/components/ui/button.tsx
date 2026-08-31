'use client';

import { forwardRef, type ButtonHTMLAttributes } from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from '@/lib/utils';

/**
 * B1（front_design 02 §5.1）：统一按钮。
 * 迁移对象：全站 18 处 `bg-brand-500 text-white hover:bg-brand-600` 及分页/ghost/danger 散写。
 * primary/danger 内置 magnetic；触屏端由 globals.css 的 pointer:coarse 规则禁用缩放。
 */
const buttonVariants = cva(
  cn(
    'inline-flex items-center justify-center gap-1.5 rounded-lg font-medium',
    'transition-colors duration-fast focus-ring disabled:opacity-50 disabled:pointer-events-none'
  ),
  {
    variants: {
      variant: {
        primary: 'bg-brand-500 text-white hover:bg-brand-600 magnetic',
        secondary: cn(
          'border border-line bg-surface-2 text-ink hover:border-brand-500',
          'dark:bg-slate-800'
        ),
        ghost: 'bg-transparent text-ink-secondary hover:bg-surface-2 hover:text-ink',
        danger: 'bg-danger text-white hover:opacity-90 magnetic',
        'danger-ghost': 'bg-transparent text-danger hover:bg-danger-soft',
      },
      size: {
        sm: 'h-8 px-3 text-sm',
        md: 'h-10 px-4 text-sm',
        lg: 'h-11 px-5 text-base',
      },
    },
    defaultVariants: {
      variant: 'primary',
      size: 'md',
    },
  }
);

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, type = 'button', ...props }, ref) => (
    <button
      ref={ref}
      type={type}
      className={cn(buttonVariants({ variant, size }), className)}
      {...props}
    />
  )
);
Button.displayName = 'Button';

export { buttonVariants };
