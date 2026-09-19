/**
 * G-4：前端输入护栏——违禁词检测 + 垃圾输入识别（纯函数，可独立单测）。
 *
 * 设计原则：轻量正则匹配（非 AI 审核），目的是给出友好提示而非阻断——
 * 后端仍有一切最终防线（CSP nonce / GlobalExceptionHandler / RerankGate）。
 * 违禁词列表仅覆盖本项目评估集中的类别，不做通用内容审核。
 */

/** 违禁/敏感词模式（旅游规划场景不应涉及的内容） */
const FORBIDDEN_PATTERNS: Array<{ re: RegExp; label: string }> = [
  { re: /爆炸物|炸弹|炸药|制作炸弹|如何制炸/, label: '危险物品制作' },
  { re: /毒品|冰毒|海洛因|大麻|买毒/, label: '毒品相关' },
  { re: /黑客|入侵系统|攻击系统|破解密码|勒索病毒|黑入/, label: '网络攻击' },
  { re: /自杀|自残|轻生/, label: '自伤相关' },
];

/** 输入护栏结果 */
export interface InputGuardResult {
  /** true=通过检查可发送 */
  ok: boolean;
  /** 未通过时的友好提示 */
  reason?: string;
}

/**
 * 违禁内容检测：匹配已知敏感词时返回友好提示（不发送请求）。
 * 设计口径：宁可漏过（后端还有防线）不可误伤（正常旅游问题绝不能被拦截）。
 */
export function checkInput(text: string): InputGuardResult {
  for (const { re, label } of FORBIDDEN_PATTERNS) {
    if (re.test(text)) {
      return {
        ok: false,
        reason: `您的问题可能涉及${label}，我无法协助。请换一个旅游相关的问题。`,
      };
    }
  }
  return { ok: true };
}

/**
 * 垃圾输入检测：检测任意 2 字符对连续重复出现 >threshold 次。
 * 典型场景："北京"重复 500 次的极限超长输入。
 */
export function isRepetitiveSpam(text: string, threshold = 20): boolean {
  if (text.length < 50) return false;
  const seen = new Map<string, number>();
  for (let i = 0; i < text.length - 1; i += 2) {
    const pair = text.slice(i, i + 2);
    const count = (seen.get(pair) || 0) + 1;
    seen.set(pair, count);
    if (count > threshold) return true;
  }
  return false;
}

/**
 * 输入长度验证：与后端 ChatController 200 字上限对齐。
 */
export function validateLength(text: string, max = 200): InputGuardResult {
  const trimmed = text.trim();
  if (!trimmed) {
    return { ok: false, reason: '请输入消息内容' };
  }
  if (trimmed.length > max) {
    return { ok: false, reason: `消息过长（${trimmed.length}字），请精简至${max}字以内` };
  }
  return { ok: true };
}

/**
 * 综合护栏：长度 + 垃圾 + 违禁 一次检查全部。
 * 调用方只需一个函数即可完成全部前端前置验证。
 */
export function guardInput(text: string): InputGuardResult {
  const len = validateLength(text);
  if (!len.ok) return len;

  if (isRepetitiveSpam(text.trim())) {
    return { ok: false, reason: '检测到大量重复内容，请输入有意义的问题' };
  }

  return checkInput(text.trim());
}
