import { cn } from '@/lib/utils';

/**
 * B2（front_design 03 §4.6，升级 F-20）：
 * 基础骨架块升级为 pulse + shimmer 扫光（globals.css .skeleton-shimmer）；
 * CardGridSkeleton 支持 columns（修复行程列表 md:2 列下骨架固定 3 列的形状错配）。
 */
export function Skeleton({ className, shimmer = true }: { className?: string; shimmer?: boolean }) {
  return (
    <div
      className={cn(
        'rounded-lg bg-slate-200 dark:bg-slate-800',
        shimmer && 'skeleton-shimmer',
        className
      )}
    />
  );
}

export function CardGridSkeleton({
  count = 6,
  columns = 3,
  className,
}: {
  count?: number;
  /** 实际网格列数（lg 断点），用于形状匹配：行程列表传 2，景点传 3 */
  columns?: number;
  className?: string;
}) {
  return (
    <div
      className={cn(
        'grid gap-4',
        columns === 2 ? 'md:grid-cols-2' : 'md:grid-cols-2 lg:grid-cols-3',
        className
      )}
    >
      {Array.from({ length: count }).map((_, i) => (
        <div key={i} className="card space-y-3 p-4">
          <Skeleton className="h-5 w-2/3" />
          <Skeleton className="h-4 w-1/3" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-4/5" />
        </div>
      ))}
    </div>
  );
}
