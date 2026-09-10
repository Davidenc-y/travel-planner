import type { R } from '@/types';
import { planningApi } from './http';

// ==================== User（F121：个人资料/头像） ====================
export const userApi = {
  me: () =>
    planningApi.get<R<import('@/types').UserInfo>>('/api/v1/users/me'),
  uploadAvatar: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    // 不手动设 Content-Type（axios 自动带 boundary）
    return planningApi.post<R<string>>('/api/v1/users/avatar', fd);
  },
  /** M5-1：绑定邮箱（注册未填时后补；格式与唯一性由后端校验） */
  updateEmail: (email: string) =>
    planningApi.put<R<void>>('/api/v1/users/email', { email }),
  /** U1：个人中心使用统计（rangeDays：7|30，作用于趋势图/模型用量） */
  usageStats: (rangeDays: 7 | 30) =>
    planningApi.get<R<import('@/types').UsageStats>>('/api/v1/users/me/usage-stats', {
      params: { rangeDays },
    }),
};
