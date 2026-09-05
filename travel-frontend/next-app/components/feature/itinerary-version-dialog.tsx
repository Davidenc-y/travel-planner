'use client';

import { useEffect, useState } from 'react';
import { toast } from 'sonner';
import { Dialog } from '@/components/ui/dialog';
import { itineraryApi, getErrorMessage } from '@/lib/api';

/**
 * M13-2h/M15-5：行程历史版本对话框（卡片弹窗与完整详情页共用能力）。
 * 点击版本行即直接切换（无确认弹窗），版本总数固定，不再递增版本号。
 */
export function ItineraryVersionDialog({
  open,
  itineraryId,
  activeVersion,
  onClose,
  onActivated,
}: {
  open: boolean;
  itineraryId: number | null;
  activeVersion?: number | null;
  onClose: () => void;
  onActivated?: () => void;
}) {
  const [versions, setVersions] = useState<Array<Record<string, unknown>>>([]);
  const [versionDetail, setVersionDetail] = useState<Record<string, unknown> | null>(null);
  const [busy, setBusy] = useState(false);
  const [switching, setSwitching] = useState(false);

  useEffect(() => {
    if (!open || itineraryId == null) return;
    setVersionDetail(null);
    setBusy(true);
    itineraryApi
      .versions(itineraryId)
      .then((res) => setVersions(res.data.data ?? []))
      .catch((err) => toast.error('版本加载失败: ' + getErrorMessage(err)))
      .finally(() => setBusy(false));
  }, [open, itineraryId]);

  /** 点击版本行：读取该版本详情并直接切换；无确认，避免来回切换步骤过多。 */
  const switchVersion = async (version: number) => {
    if (itineraryId == null || switching) return;
    setSwitching(true);
    try {
      const detailRes = await itineraryApi.version(itineraryId, version);
      setVersionDetail(detailRes.data.data);
      if (activeVersion !== version) {
        const res = await itineraryApi.activateVersion(itineraryId, version);
        toast.success(`已切换至 v${String(res.data.data?.activeVersion ?? version)}`);
        onActivated?.();
      }
    } catch (err) {
      toast.error('版本切换失败: ' + getErrorMessage(err));
    } finally {
      setSwitching(false);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} className="max-w-xl p-4" ariaLabel="历史版本">
      <div>
        <h3 className="mb-3 font-semibold">历史版本</h3>
        {busy && <p className="text-sm text-ink-faint">加载中…</p>}
        {!busy && versions.length === 0 && (
          <p className="text-sm text-ink-faint">暂无历史版本</p>
        )}
        <div className="max-h-72 space-y-2 overflow-y-auto">
          {versions.map((v) => {
            const num = Number(v.version);
            const isActive = activeVersion != null && num === activeVersion;
            return (
              <button
                key={String(v.version)}
                type="button"
                disabled={switching}
                onClick={() => switchVersion(num)}
                className={
                  'w-full rounded-lg border px-3 py-2 text-left text-sm hover:border-brand-400 focus-ring '
                  + (isActive
                    ? 'border-brand-500 bg-brand-500/10'
                    : 'border-line bg-surface')
                }
              >
                <span className="font-medium">v{String(v.version)}</span>
                <span className="ml-2 text-ink-faint">{String(v.createdAt ?? '')}</span>
                {isActive && (
                  <span className="ml-2 rounded bg-brand-500 px-1.5 py-0.5 text-[10px] font-medium text-white">
                    当前使用
                  </span>
                )}
              </button>
            );
          })}
        </div>
        {versionDetail && (
          <div className="mt-3 rounded-lg border border-line bg-surface-2 p-3 text-xs">
            <VersionDiff diff={versionDetail.versionDiff as Record<string, unknown> | null} />
            <p className="mt-2 text-ink-faint">
              点击上方版本行可直接切换，当前版本不会新增。
            </p>
          </div>
        )}
      </div>
    </Dialog>
  );
}

function VersionDiff({ diff }: { diff: Record<string, unknown> | null }) {
  if (!diff) return <p className="text-ink-faint">v1 初始版本，无 diff</p>;
  const rows: Array<[string, string, string]> = [
    ['kept', '保留', 'text-ink-secondary'],
    ['adjusted', '调整', 'text-amber-600'],
    ['added', '新增', 'text-emerald-600'],
    ['removed', '删除', 'text-red-500'],
  ];
  return (
    <div className="space-y-1">
      {rows.map(([key, label, color]) => {
        const items = diff[key];
        if (!Array.isArray(items) || items.length === 0) return null;
        return (
          <div key={key}>
            <span className={`font-medium ${color}`}>{label}</span>
            <span className="ml-1 text-ink-secondary">{items.join('、')}</span>
          </div>
        );
      })}
    </div>
  );
}
