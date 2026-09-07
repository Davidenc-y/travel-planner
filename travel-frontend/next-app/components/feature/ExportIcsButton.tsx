'use client';

import { useState } from 'react';
import { CalendarPlus } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { itineraryApi } from '@/lib/api';

/**
 * M27（E7）/M28-5：导出日历按钮——拉取 .ics Blob 并触发浏览器保存。
 * 共享组件：行程详情页与列表页名片弹窗复用（M28-5 前弹窗无导出入口，
 * 且 /itinerary/[id] 全详情页无导航入口，用户实际可达视图只有弹窗）。
 */
export function ExportIcsButton({ itineraryId }: { itineraryId: number }) {
  const [busy, setBusy] = useState(false);
  const download = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const blob = await itineraryApi.exportIcs(itineraryId);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `itinerary-${itineraryId}.ics`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      toast.success('日历文件已导出，可导入手机/系统日历');
    } catch {
      toast.error('日历导出失败');
    } finally {
      setBusy(false);
    }
  };
  return (
    <Button variant="secondary" size="sm" onClick={download} disabled={busy}>
      <CalendarPlus className="h-3.5 w-3.5" /> {busy ? '导出中…' : '导出日历'}
    </Button>
  );
}
