'use client';

import { useCallback, useEffect, useState } from 'react';
import { isPreferenceEmpty, type PreferenceTags } from '@/lib/schemas';

/** M28-6：导出供跨页面（版本弹窗）直写偏好标签用（挂载时恢复即生效） */
export const PREFS_STORAGE_KEY = 'travel.chat.prefs';
const STORAGE_KEY = PREFS_STORAGE_KEY;

/** 按会话隔离的偏好标签状态（M23c，E4）——localStorage 持久化（跨刷新保留）。 */
export function useSessionPreference(currentSessionId?: string | null) {
  const [prefs, setPrefs] = useState<Record<string, PreferenceTags>>({});

  /** 挂载时一次性从 localStorage 恢复。 */
  useEffect(() => {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        setPrefs(JSON.parse(raw) as Record<string, PreferenceTags>);
      }
    } catch {
      // 损坏的持久化数据按空处理
    }
  }, []);

  const persist = useCallback((next: Record<string, PreferenceTags>) => {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // 存储满/不可写时静默降级（状态仍在内存）
    }
  }, []);

  const update = useCallback((sid: string, tags: PreferenceTags) => {
    setPrefs((prev) => {
      const next = { ...prev, [sid]: tags };
      persist(next);
      return next;
    });
  }, [persist]);

  const setTags = useCallback(
    (sid: string, tags: PreferenceTags) => {
      update(sid, isPreferenceEmpty(tags) ? {} : tags);
    },
    [update],
  );

  const clear = useCallback(
    (sid: string) => {
      update(sid, {});
    },
    [update],
  );

  const tagsOf = useCallback(
    (sid?: string | null): PreferenceTags => (sid ? prefs[sid] ?? {} : {}),
    [prefs],
  );

  return { tagsOf, setTags, clear };
}
