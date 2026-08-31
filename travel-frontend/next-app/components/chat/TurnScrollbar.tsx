'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { ChatMessage } from '@/types';
import { cn } from '@/lib/utils';

/**
 * C1r2（用户反馈调整）：左缘记录式滚动条 v3。
 *
 * (1) 悬停不改变鼠标样式（无 cursor 类）；
 * (2) 刻度均匀分布（固定间距 GAP）；刻度群 = 可滑动滚轮：
 *     - 轮次少（刻度群不超出刻度尺）时整体【垂直居中】静默展示（C1r2 默认位置）；
 *     - 轮次多时进入滚轮模式，随会话滚动而上下转动（双向同步）；
 * (3) 波动效果：悬停刻度最长、邻刻度按距离衰减（带过渡），并弹出该轮内容预览卡；
 * (4) （MessageBubble 中）回答 = 用时行 + 浅色分隔线 + 正文。
 *
 * ⚠️ 实现约束（C1r1 回归教训）：容器为 absolute + 全绝对定位子元素，必须显式声明宽度；
 *    barH 必须在刻度条实际渲染后用 ResizeObserver 测量——组件在无轮次时渲染 null，
 *    若在挂载时测量会得到 0/1 并永久过期（v2 不显示的根因）。
 *
 * 映射口径：会话滚动比例 norm = scrollTop / (scrollHeight - clientHeight)；
 * 滚轮模式下刻度群偏移 O = norm × maxO；activeIdx = round(norm × (N-1))。
 */

const GAP = 14; // 相邻刻度间距（px）
const PAD = 12; // 滚轮模式下刻度尺上下留白
const BAR_WIDTH = 22;
const HOVER_SNAP = GAP * 0.6;

interface TurnInfo {
  key: string;
  userText: string;
  assistantText: string;
}

export function TurnScrollbar({
  containerRef,
  messages,
}: {
  containerRef: React.RefObject<HTMLDivElement | null>;
  messages: ChatMessage[];
}) {
  const barRef = useRef<HTMLDivElement>(null);
  const nodesRef = useRef<Map<string, HTMLElement>>(new Map());
  const draggingRef = useRef(false);
  const scrollRafRef = useRef<number | null>(null);
  const [turns, setTurns] = useState<TurnInfo[]>([]);
  const [barH, setBarH] = useState(0); // 实测刻度尺高度（渲染后测量）
  const [offset, setOffset] = useState(0); // 滚轮模式下刻度群偏移 O
  const [activeIdx, setActiveIdx] = useState(0);
  const [hovering, setHovering] = useState(false);
  const [dragging, setDragging] = useState(false);
  const [hoverIdx, setHoverIdx] = useState<number | null>(null);
  const [preview, setPreview] = useState<{ y: number; turn: TurnInfo } | null>(null);

  const show = turns.length > 0;
  const scrollEl = () => containerRef.current;

  // 组装轮次（用户消息 + 其后第一条助手回复片段）；维护 key→节点映射（跳转用）
  useEffect(() => {
    const el = scrollEl();
    const nodes = new Map<string, HTMLElement>();
    if (el) {
      el.querySelectorAll<HTMLElement>('[data-user-turn]').forEach((node) => {
        nodes.set(node.dataset.userTurn ?? '', node);
      });
    }
    nodesRef.current = nodes;
    const list: TurnInfo[] = [];
    let lastAssistant = '';
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const msg = messages[i];
      if (msg.role === 'assistant') {
        if (!lastAssistant) lastAssistant = msg.content;
        continue;
      }
      if (msg.role === 'user') {
        const key = String(msg.id ?? msg.localKey ?? `i-${i}`);
        list.unshift({ key, userText: msg.content, assistantText: lastAssistant });
        lastAssistant = '';
      }
    }
    setTurns((prev) => (prev.length === list.length ? prev : list));
  }, [messages]);

  // C1r2 修复：刻度条实际渲染后测量高度（ResizeObserver 跟随尺寸变化；show 翻转时重新挂载观察）
  useEffect(() => {
    if (!show) return undefined;
    const bar = barRef.current;
    if (!bar) return undefined;
    const measure = () => setBarH(bar.clientHeight);
    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const ro = new ResizeObserver(measure);
    ro.observe(bar);
    return () => ro.disconnect();
  }, [show]);

  const clusterH = turns.length > 0 ? (turns.length - 1) * GAP : 0;
  const fits = barH > 0 && clusterH + PAD * 2 <= barH;
  const maxO = fits ? 0 : Math.max(0, clusterH + PAD * 2 - barH);

  /** 刻度 y：居中模式（不溢出）= 垂直居中排布；滚轮模式 = PAD + i*GAP - O */
  const tickY = useCallback(
    (idx: number): number => {
      if (fits) return (barH - clusterH) / 2 + idx * GAP;
      return PAD + idx * GAP - offset;
    },
    [barH, clusterH, fits, offset]
  );

  const maxScroll = useCallback((): number => {
    const el = scrollEl();
    return el ? Math.max(0, el.scrollHeight - el.clientHeight) : 0;
  }, []);

  /** 会话滚动 → 刻度群转动（滚轮模式） */
  const syncFromScroll = useCallback(() => {
    if (draggingRef.current) return;
    const el = scrollEl();
    if (!el) return;
    const sMax = maxScroll();
    const norm = sMax > 0 ? Math.min(1, el.scrollTop / sMax) : 0;
    setOffset(norm * maxO);
    setActiveIdx(Math.round(norm * Math.max(0, turns.length - 1)));
  }, [maxO, maxScroll, turns.length]);

  // 监听会话滚动（rAF 节流）
  useEffect(() => {
    const el = scrollEl();
    if (!el) return undefined;
    const onScroll = () => {
      if (scrollRafRef.current !== null) return;
      scrollRafRef.current = requestAnimationFrame(() => {
        scrollRafRef.current = null;
        syncFromScroll();
      });
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    syncFromScroll();
    return () => {
      el.removeEventListener('scroll', onScroll);
      if (scrollRafRef.current !== null) {
        cancelAnimationFrame(scrollRafRef.current);
        scrollRafRef.current = null;
      }
    };
  }, [syncFromScroll]);

  /** 刻度尺位置 → 会话滚动（拖拽/点击，可预测映射） */
  const scrubTo = useCallback(
    (clientY: number) => {
      const el = scrollEl();
      const bar = barRef.current;
      if (!el || !bar) return;
      const rect = bar.getBoundingClientRect();
      const norm = Math.max(0, Math.min(1, (clientY - rect.top - PAD / 2) / Math.max(1, barH - PAD)));
      el.scrollTop = norm * maxScroll();
      setOffset(norm * maxO);
      setActiveIdx(Math.round(norm * Math.max(0, turns.length - 1)));
    },
    [barH, maxO, maxScroll, turns.length]
  );

  /** 悬停吸附最近刻度（波动效果 + 预览） */
  const nearestIdx = useCallback(
    (clientY: number): number | null => {
      const bar = barRef.current;
      if (!bar || turns.length === 0) return null;
      const y = clientY - bar.getBoundingClientRect().top;
      // 两种模式下统一用"当前 y 反推最近刻度索引"
      const idx = fits
        ? Math.round((y - (barH - clusterH) / 2) / GAP)
        : Math.round((y - PAD + offset) / GAP);
      if (idx < 0 || idx >= turns.length) return null;
      if (Math.abs(tickY(idx) - y) > HOVER_SNAP) return null;
      return idx;
    },
    [barH, clusterH, fits, offset, tickY, turns.length]
  );

  const onBarMouseDown = (e: React.MouseEvent) => {
    e.preventDefault();
    draggingRef.current = true;
    setDragging(true);
    setPreview(null);
    scrubTo(e.clientY);
    const onMove = (ev: MouseEvent) => {
      if (draggingRef.current) scrubTo(ev.clientY);
    };
    const onUp = () => {
      draggingRef.current = false;
      setDragging(false);
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
    };
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  };

  const onBarMouseMove = (e: React.MouseEvent) => {
    if (draggingRef.current) return;
    const idx = nearestIdx(e.clientY);
    setHoverIdx(idx);
    if (idx != null) {
      setPreview({ y: tickY(idx), turn: turns[idx] });
    } else {
      setPreview(null);
    }
  };

  /** C1r3：点击刻度直接跳转至该轮消息头（无平滑动画，用户要求即时定位） */
  const jumpTo = (turn: TurnInfo) => {
    nodesRef.current.get(turn.key)?.scrollIntoView({ block: 'start', behavior: 'auto' });
  };

  /** C3 增强：波动宽度——悬停刻度显著加长（26px），邻刻度按距离强衰减；无悬停时当前视口刻度略长 */
  const tickWidth = (idx: number): number => {
    if (hovering && hoverIdx != null) {
      const d = Math.abs(idx - hoverIdx);
      if (d === 0) return 26;
      if (d === 1) return 20;
      if (d === 2) return 15;
      if (d === 3) return 11;
      return 9;
    }
    return idx === activeIdx ? 13 : 9;
  };

  /** C3 增强：波动颜色/高度——指向刻度墨色加粗，d=1 半强调，形成明显起伏 */
  const tickClass = (idx: number): string => {
    if (hovering && hoverIdx != null) {
      const d = Math.abs(idx - hoverIdx);
      if (d === 0) return 'h-1 bg-ink';
      if (d === 1) return 'h-[3px] bg-ink-faint';
      return 'h-[2px] bg-line';
    }
    return idx === activeIdx ? 'h-[2px] bg-ink-faint' : 'h-[2px] bg-line';
  };

  if (!show) return null;

  return (
    <div
      ref={barRef}
      role="scrollbar"
      aria-label="会话轮次导航"
      aria-orientation="vertical"
      /* (1) 不修改鼠标样式（无 cursor 类）；宽度必须显式声明（见文件头约束）。
         不设 overflow-hidden：预览卡定位在容器外侧（left-full），裁剪会把它吞掉；
         滚轮模式的越界刻度已由 y 范围剔除兜底 */
      className="absolute left-0 top-0 bottom-0 z-10 select-none"
      style={{ width: BAR_WIDTH }}
      onMouseEnter={() => setHovering(true)}
      onMouseLeave={() => {
        setHovering(false);
        setHoverIdx(null);
        setPreview(null);
      }}
      onMouseDown={onBarMouseDown}
      onMouseMove={onBarMouseMove}
      onWheel={(e) => {
        // 悬停本条时滚轮驱动会话滚动（滚轮模式下刻度群经 scroll 监听同步转动）
        const el = scrollEl();
        if (el) el.scrollTop += e.deltaY;
      }}
      onClick={(e) => {
        const idx = nearestIdx(e.clientY);
        if (idx != null) jumpTo(turns[idx]);
      }}
    >
      {/* barH 测量完成前不渲染刻度（避免首帧错误定位） */}
      {barH > 0
        && turns.map((turn, idx) => {
          const y = tickY(idx);
          if (y < -GAP || y > barH + GAP) return null; // 视口外刻度不渲染（滚轮模式）
          return (
            <span
              key={turn.key}
              aria-hidden
              className={cn(
                'absolute left-1/2 rounded-full transition-all duration-150',
                tickClass(idx)
              )}
              style={{
                top: y,
                width: tickWidth(idx),
                transform: 'translateX(-50%)',
              }}
            />
          );
        })}

      {/* 轮次预览卡（悬停具体刻度时出现；pointer-events-none 防误触） */}
      {hovering && !dragging && preview && (
        <div
          className="pointer-events-none absolute left-full ml-2 z-20 w-64 rounded-xl border border-line bg-surface-1 p-3 shadow-2 animate-fade-in"
          style={{ top: preview.y, transform: 'translateY(-50%)' }}
        >
          <p className="line-clamp-2 text-xs font-semibold text-ink">
            {preview.turn.userText}
          </p>
          {preview.turn.assistantText && (
            <p className="mt-1 line-clamp-2 text-xs text-ink-secondary">
              {preview.turn.assistantText}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
