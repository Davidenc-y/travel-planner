/**
 * R2/P1：时间/时长格式化统一入口（收敛 MessageBubble/SessionList/usage-format 的分散实现）。
 * 全部为纯函数；边界语义与迁移前逐一对齐（"刚刚"/"<1分钟"等）。
 */

/** 消息时刻：同日 HH:mm，跨日 MM-DD HH:mm；非法输入返回空串（B0 修复语义） */
export function formatClockTime(iso?: string): string {
  const d = iso ? new Date(iso) : new Date();
  if (Number.isNaN(d.getTime())) return '';
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  const hm = `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  const sameDay = d.getFullYear() === now.getFullYear()
    && d.getMonth() === now.getMonth()
    && d.getDate() === now.getDate();
  return sameDay ? hm : `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${hm}`;
}

/** 短时长（执行过程摘要用）：<60s → "6.2秒/45秒"，否则 "1分8秒"；空值返回空串 */
export function formatDurationShort(ms?: number): string {
  if (!ms || ms <= 0) return '';
  if (ms < 60_000) {
    const s = ms / 1000;
    return `${s < 10 ? s.toFixed(1) : Math.round(s)}秒`;
  }
  const min = Math.floor(ms / 60_000);
  const sec = Math.round((ms % 60_000) / 1000);
  return `${min}分${sec}秒`;
}

/** 长时长（U1 统计卡用）："4小时50分钟"/"45分钟"；不足 1 分钟 "<1分钟" */
export function formatDurationMs(ms: number): string {
  if (!Number.isFinite(ms) || ms <= 0) return '<1分钟';
  const totalMin = Math.floor(ms / 60_000);
  if (totalMin < 1) return '<1分钟';
  const hours = Math.floor(totalMin / 60);
  const minutes = totalMin % 60;
  if (hours <= 0) return `${minutes}分钟`;
  if (minutes === 0) return `${hours}小时`;
  return `${hours}小时${minutes}分钟`;
}

/** 相对时间（会话列表用）：最大时间单位取整，最小单位分，不足 1 分钟"刚刚" */
export function formatRelativeTime(iso: string | undefined, now: number): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '';
  const diff = Math.max(0, now - d.getTime());
  const minutes = Math.floor(diff / 60_000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes}分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}小时`;
  const days = Math.floor(hours / 24);
  if (days < 30) return `${days}天`;
  const months = Math.floor(days / 30);
  if (months < 12) return `${months}个月`;
  return `${Math.floor(months / 12)}年`;
}
