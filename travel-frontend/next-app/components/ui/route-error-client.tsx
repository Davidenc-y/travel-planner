'use client';

import { RouteError } from '@/components/ui/route-error';

/**
 * RK-18/E-9：路由 error.tsx 的可复用 client 边界包装（原 app/error.tsx 逻辑收敛单源；
 * 各路由 error.tsx 单行重导出本文件，消除三份相同包装函数）。
 */
export default function RouteErrorClient({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return <RouteError error={error} reset={reset} />;
}
