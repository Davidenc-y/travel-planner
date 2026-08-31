'use client';

import { forwardRef, type InputHTMLAttributes, type TextareaHTMLAttributes } from 'react';
import { cn } from '@/lib/utils';

/**
 * B1（front_design 02 §5.2）：统一输入件。
 * 迁移对象：全站 30+ 处 `px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700
 * bg-transparent focus:ring-2 focus:ring-brand-500 outline-none`。
 * error 状态替代 plan 页手写的红字提示。
 */

const fieldBase = cn(
  'w-full rounded-lg border bg-transparent px-4 py-2.5 text-sm outline-none transition-all',
  'border-line placeholder:text-ink-faint',
  'focus:border-brand-500 focus:ring-2 focus:ring-brand-500',
  'disabled:opacity-50'
);

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  error?: string | null;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, error, ...props }, ref) => (
    <>
      <input
        ref={ref}
        aria-invalid={!!error}
        className={cn(fieldBase, error && 'border-danger focus:ring-danger', className)}
        {...props}
      />
      {error ? (
        <p className="mt-1 text-xs text-danger" role="alert">
          {error}
        </p>
      ) : null}
    </>
  )
);
Input.displayName = 'Input';

export interface TextareaProps extends TextareaHTMLAttributes<HTMLTextAreaElement> {
  error?: string | null;
}

export const Textarea = forwardRef<HTMLTextAreaElement, TextareaProps>(
  ({ className, error, ...props }, ref) => (
    <>
      <textarea
        ref={ref}
        aria-invalid={!!error}
        className={cn(fieldBase, 'resize-none leading-relaxed', error && 'border-danger', className)}
        {...props}
      />
      {error ? (
        <p className="mt-1 text-xs text-danger" role="alert">
          {error}
        </p>
      ) : null}
    </>
  )
);
Textarea.displayName = 'Textarea';
