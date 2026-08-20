'use client';

import { cn } from '@/lib/utils';
import { SmartImage } from './smart-image';

const SIZES = {
  sm: 'h-8 w-8 text-sm',
  md: 'h-10 w-10 text-base',
  lg: 'h-16 w-16 text-2xl',
};

/** F121：圆形用户头像（MinIO 图 + 首字占位兜底） */
export function UserAvatar({
  name,
  src,
  size = 'md',
  className,
}: {
  name?: string | null;
  src?: string | null;
  size?: keyof typeof SIZES;
  className?: string;
}) {
  return (
    <SmartImage
      src={src}
      alt={name ? `${name} 的头像` : '头像'}
      fallbackText={name?.charAt(0).toUpperCase() || 'U'}
      className={cn('rounded-full', SIZES[size], className)}
      imgClassName="rounded-full"
    />
  );
}
