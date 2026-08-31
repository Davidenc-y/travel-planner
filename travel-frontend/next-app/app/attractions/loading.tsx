import { CardGridSkeleton } from '@/components/ui/skeleton';

/** A1：景点浏览路由级流式骨架（lg:3 列形状匹配） */
export default function AttractionsLoading() {
  return (
    <div>
      <div className="h-10 w-40 mb-6 rounded-lg bg-slate-200 dark:bg-slate-800 skeleton-shimmer" />
      <CardGridSkeleton count={6} columns={3} />
    </div>
  );
}
