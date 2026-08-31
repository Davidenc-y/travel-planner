'use client';

import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { cn } from '@/lib/utils';

/**
 * B1（front_design 02 §5.4，修复 F-08/F-09）：自研轻量 Dialog。
 * - 打开：锁定 body 滚动 + 遮罩 fade + 面板 pop-in（03 §4.4）
 * - 关闭：Esc / × / 遮罩点击（保留 F103 语义）；退出动画用"延迟卸载"模式（03 §2，零依赖）
 * - 焦点：打开时聚焦面板、Tab 圈禁、关闭后还原触发元素焦点
 * - C5：可选 `originRect`（触发元素矩形）启用 **Container Transform**——面板从该矩形
 *   位置/尺寸 FLIP 展开至居中终态，关闭时逆向收回（行程卡片转场用）；
 *   prefers-reduced-motion 下由全局降级规则压为瞬时（R7）。
 */
const EXIT_MS = 120;
const CT_EXIT_MS = 240;

export interface DialogOriginRect {
  top: number;
  left: number;
  width: number;
  height: number;
}

export interface DialogProps {
  open: boolean;
  onClose: () => void;
  children: ReactNode;
  /** 面板容器附加类（尺寸/布局由调用方决定） */
  className?: string;
  /** 无关闭按钮（如确认框用底部按钮组） */
  hideClose?: boolean;
  ariaLabel?: string;
  /** C5：Container Transform 起始矩形（触发元素 getBoundingClientRect）；不传走默认 pop-in */
  originRect?: DialogOriginRect | null;
}

/** FLIP：由终态矩形与起点矩形求初始 transform（translate 至起点中心 + 缩放） */
function flipTransform(final: DOMRect, origin: DialogOriginRect): string {
  const sx = origin.width / Math.max(1, final.width);
  const sy = origin.height / Math.max(1, final.height);
  const dx = origin.left + origin.width / 2 - (final.left + final.width / 2);
  const dy = origin.top + origin.height / 2 - (final.top + final.height / 2);
  return `translate(${dx}px, ${dy}px) scale(${sx}, ${sy})`;
}

export function Dialog({ open, onClose, children, className, hideClose, ariaLabel, originRect }: DialogProps) {
  const [render, setRender] = useState(open);
  const [closing, setClosing] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);
  const restoreFocusRef = useRef<HTMLElement | null>(null);
  // C5：Container Transform 状态（仅 originRect 提供时启用）
  const useCT = !!originRect && !!open;
  const [ctPanelStyle, setCtPanelStyle] = useState<CSSProperties>({});
  const [ctContentHidden, setCtContentHidden] = useState(false);

  const requestClose = useCallback(() => {
    if (closing) return;
    const useCTNow = !!originRect && !!panelRef.current && !closing;
    setClosing(true);
    if (useCTNow && originRect) {
      // C5：逆向 FLIP——面板收回触发元素矩形，内容先淡出
      const panel = panelRef.current;
      setCtContentHidden(true);
      if (panel) {
        const final = panel.getBoundingClientRect();
        setCtPanelStyle({
          transform: flipTransform(final, originRect),
          opacity: 0.3,
          transition: 'transform 240ms cubic-bezier(0.4, 0, 1, 1), opacity 200ms ease-in',
        });
      }
      window.setTimeout(() => {
        setRender(false);
        setClosing(false);
        onClose();
      }, CT_EXIT_MS);
      return;
    }
    setClosing(true);
    window.setTimeout(() => {
      setRender(false);
      setClosing(false);
      onClose();
    }, EXIT_MS);
  }, [closing, onClose, originRect]);

  // open 状态同步 DOM 存在；打开时锁滚动并记录/聚焦
  useEffect(() => {
    if (open) {
      setRender(true);
      setClosing(false);
      restoreFocusRef.current = document.activeElement as HTMLElement | null;
      const prevOverflow = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      const t = window.setTimeout(() => panelRef.current?.focus(), 0);
      return () => {
        window.clearTimeout(t);
        document.body.style.overflow = prevOverflow;
      };
    }
    return undefined;
  }, [open]);

  // Esc 关闭 + Tab 圈禁
  useEffect(() => {
    if (!render || !open) return undefined;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        requestClose();
        return;
      }
      if (e.key === 'Tab' && panelRef.current) {
        const focusables = panelRef.current.querySelectorAll<HTMLElement>(
          'button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])'
        );
        if (focusables.length === 0) return;
        const first = focusables[0];
        const last = focusables[focusables.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };
    document.addEventListener('keydown', onKey, true);
    return () => document.removeEventListener('keydown', onKey, true);
  }, [render, open, requestClose]);

  // 关闭后还原焦点
  useEffect(() => {
    if (!open && restoreFocusRef.current) {
      restoreFocusRef.current.focus?.();
      restoreFocusRef.current = null;
    }
  }, [open]);

  // C5：Container Transform 进入动画（FLIP）
  // 面板随 render 挂载后：测量终态矩形 → 置为起点矩形（无过渡）→ 下一帧过渡到终态
  useEffect(() => {
    if (!render || !open || !useCT || !originRect) return undefined;
    const panel = panelRef.current;
    if (!panel) return undefined;
    setCtContentHidden(true);
    let raf1 = 0;
    let raf2 = 0;
    let raf3 = 0;
    raf1 = requestAnimationFrame(() => {
      const final = panel.getBoundingClientRect();
      setCtPanelStyle({
        transform: flipTransform(final, originRect),
        transition: 'none',
        opacity: 0.4,
        borderRadius: 12,
      });
      raf2 = requestAnimationFrame(() => {
        raf3 = requestAnimationFrame(() => {
          setCtPanelStyle({
            transform: 'none',
            opacity: 1,
            transition:
              'transform 300ms cubic-bezier(0.16, 1, 0.3, 1), opacity 160ms ease-out, border-radius 300ms ease',
          });
          setCtContentHidden(false);
        });
      });
    });
    return () => {
      cancelAnimationFrame(raf1);
      cancelAnimationFrame(raf2);
      cancelAnimationFrame(raf3);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [render, open, useCT]);

  if (!render) return null;

  return (
    <div
      className={cn(
        'fixed inset-0 z-[100] flex items-center justify-center bg-black/40 p-4 backdrop-blur-sm',
        closing ? 'opacity-0 transition-opacity duration-base' : 'animate-fade-in'
      )}
      onClick={requestClose}
      role="dialog"
      aria-modal="true"
      aria-label={ariaLabel}
    >
      <div
        ref={panelRef}
        tabIndex={-1}
        style={useCT ? ctPanelStyle : undefined}
        className={cn(
          'relative max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-2xl p-6 shadow-3',
          'bg-surface-1 border border-line outline-none',
          useCT
            ? '' // C5：变换样式由 ctPanelStyle 驱动
            : closing
              ? 'scale-95 opacity-0 transition-all duration-fast'
              : 'animate-pop-in',
          className
        )}
        onClick={(e) => e.stopPropagation()}
      >
        {!hideClose && (
          <button
            onClick={requestClose}
            aria-label="关闭"
            className="absolute right-3 top-3 flex h-8 w-8 items-center justify-center rounded-full text-ink-faint hover:bg-surface-2 hover:text-ink"
          >
            <X className="h-5 w-5" />
          </button>
        )}
        {useCT ? (
          /* C5：内容随面板展开延后淡入，避免缩放过程中的文字变形观感 */
          <div
            style={{
              opacity: ctContentHidden ? 0 : 1,
              transition: ctContentHidden ? 'opacity 80ms ease' : 'opacity 180ms ease 110ms',
            }}
          >
            {children}
          </div>
        ) : (
          children
        )}
      </div>
    </div>
  );
}
