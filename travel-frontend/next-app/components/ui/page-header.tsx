import type { ReactNode } from 'react';

/**
 * B1（front_design 02 §5.7）：页面标题统一外壳。
 * 收敛各页 `h1 + mb-6` 散写；actions 渲染在标题行右侧。
 */
export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex items-center justify-between gap-4">
      <div>
        <h1 className="text-2xl font-bold">{title}</h1>
        {description && <p className="mt-1 text-sm text-ink-secondary">{description}</p>}
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}
