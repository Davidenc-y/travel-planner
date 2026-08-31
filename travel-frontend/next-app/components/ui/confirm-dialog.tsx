'use client';

import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { Dialog } from './dialog';
import { Button } from './button';

/**
 * B1（front_design 02 §5.4，替换 F-08 的 3 处原生 confirm）：
 * Promise 风格确认框。Provider 挂根布局；页面 `const confirm = useConfirm()`，
 * `if (!(await confirm({ title: '确定删除此行程？' }))) return;`
 * 文案沿用被替换的 3 处原生 confirm 语义（删除行程 / 继续生成 / 结束会话）。
 */

export interface ConfirmOptions {
  title: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  /** 危险操作（删除/结束会话）：确认按钮用 danger 色 */
  danger?: boolean;
}

type ResolveFn = (v: boolean) => void;

interface ConfirmContextValue {
  confirm: (opts: ConfirmOptions) => Promise<boolean>;
}

const ConfirmContext = createContext<ConfirmContextValue | null>(null);

interface ConfirmState {
  opts: ConfirmOptions;
  resolve: ResolveFn;
}

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<ConfirmState | null>(null);
  const stateRef = useRef<ConfirmState | null>(null);
  stateRef.current = state;

  const confirm = useCallback((opts: ConfirmOptions) => {
    return new Promise<boolean>((resolve) => {
      setState({ opts, resolve });
    });
  }, []);

  const settle = useCallback((v: boolean) => {
    stateRef.current?.resolve(v);
    setState(null);
  }, []);

  const value = useMemo(() => ({ confirm }), [confirm]);

  return (
    <ConfirmContext.Provider value={value}>
      {children}
      <Dialog
        open={!!state}
        onClose={() => settle(false)}
        hideClose
        className="max-w-sm"
        ariaLabel="确认操作"
      >
        {state && (
          <div>
            <h3 className="text-lg font-semibold">{state.opts.title}</h3>
            {state.opts.description && (
              <p className="mt-1.5 text-sm text-ink-secondary">{state.opts.description}</p>
            )}
            <div className="mt-5 flex justify-end gap-2">
              <Button variant="secondary" onClick={() => settle(false)}>
                {state.opts.cancelText ?? '取消'}
              </Button>
              <Button
                variant={state.opts.danger ? 'danger' : 'primary'}
                onClick={() => settle(true)}
              >
                {state.opts.confirmText ?? '确定'}
              </Button>
            </div>
          </div>
        )}
      </Dialog>
    </ConfirmContext.Provider>
  );
}

export function useConfirm(): (opts: ConfirmOptions) => Promise<boolean> {
  const ctx = useContext(ConfirmContext);
  if (!ctx) {
    // Provider 未挂载时兜底：退化为原生 confirm（不中断业务）
    return (opts) => Promise.resolve(typeof window !== 'undefined' && window.confirm(opts.title));
  }
  return ctx.confirm;
}
