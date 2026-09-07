import { z } from 'zod';

/**
 * M23c（E4）：偏好标签 schema——与原 /plan 表单字段同构（单源），
 * 后端 PreferenceTagsDTO 校验语义对齐（双端一致）。
 */
export const INTEREST_OPTIONS = ['文化', '自然', '美食', '购物', '亲子', '休闲'] as const;
export const PARTY_OPTIONS = ['独行', '情侣', '家庭', '朋友'] as const;

export const preferenceTagsSchema = z.object({
  destination: z.string().trim().min(2, '目的地至少 2 字').max(20, '目的地最多 20 字').optional().or(z.literal('')),
  days: z.number().int('天数须为整数').min(1, '天数至少 1 天').max(30, '天数最多 30 天').optional(),
  budget: z.number().min(0, '预算不能为负').optional(),
  party: z.enum(['独行', '情侣', '家庭', '朋友']).optional(),
  interests: z.array(z.enum(INTEREST_OPTIONS)).max(6, '兴趣标签最多 6 项').optional(),
  startDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, '日期格式 yyyy-MM-dd').optional().or(z.literal('')),
  /** M25（E4 收尾）：勾选"记住为长期偏好"（不作为标签渲染，仅随消息落画像） */
  remember: z.boolean().optional(),
});

export type PreferenceTags = z.infer<typeof preferenceTagsSchema>;

/** 是否所有字段都为空（空偏好不随消息发送）。 */
export function isPreferenceEmpty(tags: PreferenceTags): boolean {
  return (!tags.destination || tags.destination === '')
    && tags.days == null
    && tags.budget == null
    && !tags.party
    && (!tags.interests || tags.interests.length === 0)
    && (!tags.startDate || tags.startDate === '');
}

/** 偏好标签文案（发送区上侧横向标签行；每枚独立 ×）。 */
export function preferenceTagTexts(tags: PreferenceTags): { key: string; text: string }[] {
  const out: { key: string; text: string }[] = [];
  if (tags.destination && tags.destination !== '') {
    out.push({ key: 'destination', text: tags.destination });
  }
  if (tags.days != null) {
    out.push({ key: 'days', text: `${tags.days}天` });
  }
  if (tags.budget != null) {
    out.push({ key: 'budget', text: `¥${tags.budget}` });
  }
  if (tags.party) {
    out.push({ key: 'party', text: tags.party });
  }
  if (tags.interests && tags.interests.length > 0) {
    out.push({ key: 'interests', text: tags.interests.join('+') });
  }
  if (tags.startDate && tags.startDate !== '') {
    out.push({ key: 'startDate', text: tags.startDate });
  }
  return out;
}

/** 移除单枚标签（× 对应字段落空）。 */
export function removePreferenceField(tags: PreferenceTags, key: string): PreferenceTags {
  const next = { ...tags };
  switch (key) {
    case 'destination': next.destination = ''; break;
    case 'days': delete next.days; break;
    case 'budget': delete next.budget; break;
    case 'party': delete next.party; break;
    case 'interests': delete next.interests; break;
    case 'startDate': next.startDate = ''; break;
    default: break;
  }
  return next;
}

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
