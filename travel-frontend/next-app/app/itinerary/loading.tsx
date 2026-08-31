import { CardGridSkeleton } from '@/components/ui/skeleton';

/** A1：行程列表路由级流式骨架（md:2 列形状匹配） */
export default function ItineraryLoading() {
  return (
    <div>
      <div className="h-10 w-40 mb-6 rounded-lg bg-slate-200 dark:bg-slate-800 skeleton-shimmer" />
      <CardGridSkeleton count={4} columns={2} />
    </div>
  );
}
