import type { ItineraryResponse } from '@/types';
import type { PreferenceTags } from '@/lib/schemas';

// 重构 R3.0：偏好标签纯合并计算单点化。mergePreferenceSync 自 lib/schemas.ts 剪切
// （schemas.ts 原位置保留兼容 re-export）；行程回填与 mergeTags 的纯计算体自
// chat-page-content.tsx / useSessionPreference.ts 迁出。类型一律 import type，
// 避免与 schemas.ts 的 re-export 形成运行时循环引用。

/**
 * M26-F3：done.preferenceSync → 偏好标签合并。
 *
 * <p>sync 来自行程约束列（本轮有效约束的权威值），覆盖同名字段；
 * startDate/remember 等本地字段保留；budget 为纯数字字符串需转 number，
 * 非法值（NaN/负数）忽略不改写。</p>
 */
export function mergePreferenceSync(
  current: PreferenceTags,
  sync: {
    destination?: string;
    days?: number;
    budget?: string;
    party?: string;
    interests?: string[];
    startDate?: string;
  },
): PreferenceTags {
  const merged: PreferenceTags = { ...current };
  if (sync.destination) merged.destination = sync.destination;
  if (sync.days != null) merged.days = sync.days;
  if (sync.budget != null) {
    const budgetNum = Number(sync.budget);
    if (Number.isFinite(budgetNum) && budgetNum >= 0) merged.budget = budgetNum;
  }
  if (sync.party) merged.party = sync.party as PreferenceTags['party'];
  // M28-3：出发日期（yyyy-MM-dd，行程 routePlan 首日；同样为权威值覆盖）
  if (sync.startDate) merged.startDate = sync.startDate;
  if (sync.interests && sync.interests.length > 0) {
    merged.interests = sync.interests as PreferenceTags['interests'];
  }
  return merged;
}

/**
 * M28-13：勾选锚定行程 → 行程详情回填偏好标签（空值保留用户已设项——
 * 行程详情非空字段覆盖，空/缺省字段回退 prev；M28-15/M28-17 防陈闭包覆盖语义
 * 由调用方 mergeTags 的函数式更新保证，本函数只做纯计算）。
 */
export function mergePreferenceFromItinerary(
  prev: PreferenceTags,
  d: ItineraryResponse,
): PreferenceTags {
  return {
    ...prev,
    destination: d.destination ?? prev.destination,
    days: d.days ?? prev.days,
    budget: d.budget ?? prev.budget,
    // 后端词表与 PARTY_OPTIONS/INTEREST_OPTIONS 同源（parseParty/parseInterests）
    party: (d.party as typeof prev.party) ?? prev.party,
    interests: (d.interests as typeof prev.interests)?.length
      ? (d.interests as typeof prev.interests)
      : prev.interests,
    startDate: d.startDate ?? prev.startDate,
  };
}

/**
 * M28-15：useSessionPreference.mergeTags 的纯计算体——按会话键把 patch 合并进
 * 偏好记录（键缺失时 patch 以空标签为基底），React state/localStorage 持久化
 * 仍由 hook 内 setPrefs/persist 编排。
 */
export function mergeTagsAtKey(
  prev: Record<string, PreferenceTags>,
  sid: string,
  patch: (prev: PreferenceTags) => PreferenceTags,
): Record<string, PreferenceTags> {
  return { ...prev, [sid]: patch(prev[sid] ?? {}) };
}
