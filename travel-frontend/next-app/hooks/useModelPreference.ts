'use client';

import { useCallback, useEffect, useState } from 'react';

const MODEL_PREF_KEY = 'travel.model';

/**
 * M7 Batch 3：用户模型偏好（localStorage 持久化，仿 next-themes 模式）。
 *
 * <p>'' = 智能默认（不传 model，走后端角色默认）；选择具体 key 后持久化，
 * 聊天页/规划页共用同一 key；清除偏好回退默认。SSR 安全：初始 ''，
 * mount 后从 localStorage 水合，避免 hydration 不一致。</p>
 */
export function useModelPreference() {
  const [model, setModel] = useState('');

  useEffect(() => {
    try {
      const saved = localStorage.getItem(MODEL_PREF_KEY);
      if (saved) setModel(saved);
    } catch {
      // localStorage 不可用时保持默认
    }
  }, []);

  const select = useCallback((key: string) => {
    setModel(key);
    try {
      if (key) {
        localStorage.setItem(MODEL_PREF_KEY, key);
      } else {
        localStorage.removeItem(MODEL_PREF_KEY);
      }
    } catch {
      // 忽略持久化失败（仅本次会话生效）
    }
  }, []);

  return { model, select };
}
