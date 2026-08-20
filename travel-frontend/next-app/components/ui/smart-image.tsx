'use client';

import { useEffect, useMemo, useState } from 'react';
import { ImageOff } from 'lucide-react';
import { cn } from '@/lib/utils';
import { resolveImageSrc } from '@/lib/image-url';
import { Skeleton } from './skeleton';

interface SmartImageProps {
  src?: string | null;
  alt: string;
  /** 容器尺寸/圆角（容器负责布局） */
  className?: string;
  /** img 自身样式（如 object-fit） */
  imgClassName?: string;
  /** 失败/无图占位文本（取首字符展示） */
  fallbackText?: string;
  /** 首屏图片置 eager，其余懒加载 */
  priority?: boolean;
  /** F121/P2：点击放大查看 */
  zoomable?: boolean;
}

/**
 * F121：统一图片组件（MinIO 代理/直连/外部 URL 三态 + 懒加载 + 失败兜底 + 点击放大）。
 * 不用 next/image：MinIO 无裁剪能力、URL 由网关动态生成（理由见 F121 方案 5.1）。
 */
export function SmartImage({
  src,
  alt,
  className,
  imgClassName,
  fallbackText,
  priority,
  zoomable,
}: SmartImageProps) {
  const rawSrc = useMemo(() => resolveImageSrc(src), [src]);
  const [finalSrc, setFinalSrc] = useState<string | null>(
    rawSrc && !rawSrc.includes('/api/v1/files/presign?') ? rawSrc : null,
  );
  const [status, setStatus] = useState<'loading' | 'ok' | 'error'>(
    rawSrc && !rawSrc.includes('/api/v1/files/presign?') ? 'loading' : 'error',
  );
  const [zoom, setZoom] = useState(false);

  useEffect(() => {
    setZoom(false);
    if (!rawSrc) {
      setFinalSrc(null);
      setStatus('error');
      return;
    }
    if (!rawSrc.includes('/api/v1/files/presign?')) {
      setFinalSrc(rawSrc);
      setStatus('loading');
      return;
    }
    // presign 模式：先向接口换取签名 URL（默认 proxy 模式不走此分支）
    let cancelled = false;
    setStatus('loading');
    fetch(rawSrc)
      .then((r) => r.json())
      .then((d) => {
        if (cancelled) return;
        const url = d?.data?.url;
        if (url) {
          setFinalSrc(url);
        } else {
          setFinalSrc(null);
          setStatus('error');
        }
      })
      .catch(() => {
        if (!cancelled) {
          setFinalSrc(null);
          setStatus('error');
        }
      });
    return () => {
      cancelled = true;
    };
  }, [rawSrc]);

  if (!finalSrc) {
    return (
      <div
        className={cn(
          'flex items-center justify-center bg-slate-100 text-slate-400 dark:bg-slate-800',
          className,
        )}
      >
        {fallbackText ? (
          <span className="font-bold">{fallbackText.charAt(0)}</span>
        ) : (
          <ImageOff className="h-5 w-5" />
        )}
      </div>
    );
  }

  return (
    <>
      <div className={cn('relative overflow-hidden bg-slate-100 dark:bg-slate-800', className)}>
        {status === 'loading' && <Skeleton className="absolute inset-0 rounded-none" />}
        {status === 'error' ? (
          <div className="absolute inset-0 flex items-center justify-center text-slate-400">
            {fallbackText ? (
              <span className="font-bold">{fallbackText.charAt(0)}</span>
            ) : (
              <ImageOff className="h-5 w-5" />
            )}
          </div>
        ) : (
          <img
            src={finalSrc}
            alt={alt}
            loading={priority ? 'eager' : 'lazy'}
            decoding="async"
            onLoad={() => setStatus('ok')}
            onError={() => setStatus('error')}
            onClick={zoomable ? () => setZoom(true) : undefined}
            className={cn(
              'h-full w-full object-cover transition-opacity',
              status === 'ok' ? 'opacity-100' : 'opacity-0',
              imgClassName,
              zoomable && 'cursor-zoom-in',
            )}
          />
        )}
      </div>
      {zoom && status === 'ok' && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
          onClick={() => setZoom(false)}
        >
          <img
            src={finalSrc}
            alt={alt}
            className="max-h-full max-w-full rounded-lg object-contain"
          />
        </div>
      )}
    </>
  );
}
