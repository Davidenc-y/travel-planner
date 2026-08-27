'use client';

import { useEffect, useMemo, useRef, useState } from 'react';
import { MapPin } from 'lucide-react';
import { attractionApi } from '@/lib/api';
import { cn } from '@/lib/utils';

// M7-1：后端城市列表失败时的降级常量（与景点页 FALLBACK_CITY_OPTIONS 同口径）
const FALLBACK_CITY_OPTIONS = ['北京', '上海', '广州', '深圳', '杭州', '成都', '西安', '厦门', '南京', '重庆', '武汉', '长沙'];
const MAX_OPTIONS = 8;

interface DestinationAutocompleteProps {
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  disabled?: boolean;
}

/**
 * M7-1：目的地输入框 + 城市模糊匹配自动补全。
 *
 * 数据源：`attractionApi.listCities()`（后端全部去重城市，失败静默降级内置 12 城）。
 * 匹配规则：精确（含去“市”后缀）> 前缀 > 包含，取前 8 项；
 * 键盘：↑/↓ 移动、Enter 选中、Esc 关闭；点击外部关闭。
 * 选中后回填完整城市名；仍允许自由输入（后端以原文规划）。
 */
export function DestinationAutocomplete({
  value,
  onChange,
  placeholder,
  disabled,
}: DestinationAutocompleteProps) {
  const [cities, setCities] = useState<string[]>(FALLBACK_CITY_OPTIONS);
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const wrapRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLUListElement>(null);

  // M5-1 同款动态城市加载：后端可用则替换内置列表，失败保持降级常量
  useEffect(() => {
    let cancelled = false;
    attractionApi.listCities()
      .then((res) => {
        if (cancelled) return;
        const data = res.data.data || [];
        if (data.length > 0) setCities(data);
      })
      .catch(() => {
        // 静默降级内置城市列表（不打断输入）
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 点击组件外部关闭下拉
  useEffect(() => {
    const onPointerDown = (e: PointerEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, []);

  const query = value.trim().toLowerCase();
  const matches = useMemo(() => {
    if (!query) return [];
    const norm = (s: string) => s.trim().toLowerCase();
    const withoutSuffix = (s: string) => (norm(s).endsWith('市') ? norm(s).slice(0, -1) : norm(s));
    const queryBase = withoutSuffix(query);
    const scored: { city: string; score: number }[] = [];
    for (const city of cities) {
      const n = norm(city);
      const base = withoutSuffix(city);
      if (n === query || base === query || n === queryBase || base === queryBase) {
        scored.push({ city, score: 0 });
      } else if (n.startsWith(query) || n.startsWith(queryBase) || base.startsWith(queryBase)) {
        scored.push({ city, score: 1 });
      } else if (n.includes(query) || base.includes(queryBase)) {
        scored.push({ city, score: 2 });
      }
    }
    return scored
      .sort((a, b) => a.score - b.score || a.city.localeCompare(b.city, 'zh'))
      .slice(0, MAX_OPTIONS)
      .map((x) => x.city);
  }, [cities, query]);

  const select = (city: string) => {
    onChange(city);
    setOpen(false);
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (disabled) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      if (!open) {
        if (query) {
          setOpen(true);
          setActiveIndex(0);
        }
      } else {
        setActiveIndex((i) => (matches.length ? Math.min(i + 1, matches.length - 1) : 0));
      }
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      setActiveIndex((i) => Math.max(i - 1, 0));
    } else if (e.key === 'Enter') {
      if (open && matches.length > 0) {
        e.preventDefault();
        select(matches[Math.min(activeIndex, matches.length - 1)]);
      }
    } else if (e.key === 'Escape') {
      setOpen(false);
    }
  };

  // 输入变化：重置高亮并展开下拉
  const handleChange = (v: string) => {
    onChange(v);
    setActiveIndex(0);
    setOpen(v.trim().length > 0);
  };

  // 键盘高亮项滚动可见
  useEffect(() => {
    const el = listRef.current?.children[activeIndex] as HTMLElement | undefined;
    el?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex]);

  const showPanel = open && query.length > 0;

  return (
    <div ref={wrapRef} className="relative">
      <div className="relative">
        <MapPin className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
        <input
          value={value}
          onChange={(e) => handleChange(e.target.value)}
          onFocus={() => {
            if (value.trim().length > 0) setOpen(true);
          }}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          placeholder={placeholder}
          role="combobox"
          aria-expanded={showPanel}
          aria-controls="destination-autocomplete-list"
          aria-activedescendant={showPanel && matches[activeIndex]
            ? `destination-option-${activeIndex}` : undefined}
          className="w-full pl-9 pr-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-700 bg-transparent focus:ring-2 focus:ring-brand-500 focus:border-transparent outline-none transition-all disabled:opacity-50"
        />
      </div>
      {showPanel && (
        <ul
          id="destination-autocomplete-list"
          ref={listRef}
          role="listbox"
          className="absolute z-20 mt-1 max-h-64 w-full overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 shadow-lg"
        >
          {matches.length === 0 ? (
            <li className="px-3 py-2 text-sm text-slate-400">无匹配城市</li>
          ) : (
            matches.map((city, idx) => (
              <li key={city}>
                <button
                  type="button"
                  id={`destination-option-${idx}`}
                  role="option"
                  aria-selected={idx === activeIndex}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => select(city)}
                  className={cn(
                    'flex w-full items-center gap-2 px-3 py-2 text-left text-sm transition-colors',
                    idx === activeIndex
                      ? 'bg-brand-50 dark:bg-brand-900/30 text-brand-600 dark:text-brand-300'
                      : 'hover:bg-slate-100 dark:hover:bg-slate-800'
                  )}
                >
                  <MapPin className="h-3.5 w-3.5 flex-shrink-0 text-slate-400" />
                  {city}
                </button>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
