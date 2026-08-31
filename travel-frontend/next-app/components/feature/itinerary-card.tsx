'use client';

import { useRef } from 'react';
import { Loader2, MapPin, Calendar, DollarSign, Trash2, RotateCcw } from 'lucide-react';
import type { ItineraryResponse } from '@/types';
import type { DialogOriginRect } from '@/components/ui/dialog';
import { formatCurrency, formatDate } from '@/lib/utils';
import { ITINERARY_STATUS } from '@/lib/constants';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';

/**
 * R2/C-1（front_design 11 §3-C1）：行程卡片组件。
 * 从列表页抽出（渐变头/徽标/续跑/删除），卡片点击时**内部捕获矩形**并回调，
 * 供 C5 Container Transform 使用；列表页只保留数据与状态编排。
 */

// B3（04 §4.4，F-15）：目的地色卡头——按目的地字符串哈希取 6 色渐变 + 首字
const DESTINATION_GRADIENTS = [
  'from-brand-400 to-brand-600',
  'from-emerald-400 to-emerald-600',
  'from-violet-400 to-violet-600',
  'from-amber-400 to-orange-500',
  'from-rose-400 to-rose-600',
  'from-sky-400 to-cyan-600',
];

function destinationGradient(destination: string): string {
  let hash = 0;
  for (let i = 0; i < destination.length; i += 1) {
    hash = (hash * 31 + destination.charCodeAt(i)) >>> 0;
  }
  return DESTINATION_GRADIENTS[hash % DESTINATION_GRADIENTS.length];
}

export interface ItineraryCardProps {
  item: ItineraryResponse;
  /** C5：点击回调（卡片内部捕获自身矩形作为转场起点） */
  onOpen: (originRect: DialogOriginRect) => void;
  onDelete: (id: number) => void;
  onResume: (id: number) => void;
  resuming?: boolean;
}

export function ItineraryCard({ item, onOpen, onDelete, onResume, resuming }: ItineraryCardProps) {
  const rootRef = useRef<HTMLDivElement>(null);

  /** C5：以卡片当前矩形为转场起点（鼠标与键盘共用） */
  const open = () => {
    const rect = rootRef.current?.getBoundingClientRect();
    onOpen(
      rect
        ? { top: rect.top, left: rect.left, width: rect.width, height: rect.height }
        : { top: 0, left: 0, width: 0, height: 0 }
    );
  };

  /** M4-9 + B3（F-15）：状态徽标（GENERATED 成功态补齐） */
  const statusBadge = (status?: string) => {
    if (status === ITINERARY_STATUS.GENERATING) {
      return (
        <Badge tone="warning" className="animate-pulse-soft">
          <Loader2 className="h-3 w-3 animate-spin" />生成中
        </Badge>
      );
    }
    if (status === ITINERARY_STATUS.FAILED) {
      return <Badge tone="danger">失败·可续跑</Badge>;
    }
    if (status === ITINERARY_STATUS.GENERATED) {
      return <Badge tone="success">已完成</Badge>;
    }
    return null;
  };

  return (
    <div
      ref={rootRef}
      className="card overflow-hidden hover:shadow-2 transition-shadow duration-base magnetic cursor-pointer animate-rise"
      onClick={open}
      role="button"
      tabIndex={0}
      onKeyDown={(e) => e.key === 'Enter' && open()}
    >
      {/* F-15：目的地色卡头（首字锚点，纯前端生成） */}
      <div
        className={`flex h-14 items-center gap-3 bg-gradient-to-r px-5 text-white ${destinationGradient(item.destination)}`}
        aria-hidden
      >
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-white/25 text-sm font-bold">
          {item.destination?.charAt(0) || '行'}
        </span>
        <span className="text-sm font-medium opacity-95">{item.destination}</span>
      </div>
      <div className="p-5 flex items-start justify-between">
        <div className="flex-1">
          <div className="flex items-center gap-2 mb-2">
            <h3 className="font-semibold text-lg">{item.title}</h3>
            {statusBadge(item.status)}
          </div>
          <div className="space-y-1 text-sm text-ink-secondary">
            <p className="flex items-center gap-1.5">
              <MapPin className="h-3.5 w-3.5" /> {item.destination}
            </p>
            <p className="flex items-center gap-1.5">
              <Calendar className="h-3.5 w-3.5" /> {item.days} 天
            </p>
            <p className="flex items-center gap-1.5">
              <DollarSign className="h-3.5 w-3.5" /> {formatCurrency(item.estimatedCost)}
            </p>
            <p className="text-xs text-ink-faint">{formatDate(item.generatedAt)}</p>
          </div>
          {/* M4-9/M6-52：仅可续状态（FAILED/僵尸 GENERATING）显示继续生成；
              非僵尸 GENERATING 只显示"生成中"，避免与后台在途生成并发双跑 */}
          {item.resumable && (
            <Button
              size="sm"
              disabled={resuming}
              onClick={(e) => { e.stopPropagation(); onResume(item.id); }}
              className="mt-2"
            >
              {resuming ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RotateCcw className="h-3.5 w-3.5" />}
              {resuming ? '续跑中…' : '继续生成'}
            </Button>
          )}
        </div>
        <button
          onClick={(e) => { e.stopPropagation(); onDelete(item.id); }}
          className="p-1.5 rounded-lg hover:bg-danger-soft text-ink-faint hover:text-danger transition-colors focus-ring"
          aria-label={`删除行程 ${item.title}`}
        >
          <Trash2 className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}
