/**
 * FE-S1a（20260912 前端专项）：markdown 链接协议白名单纯函数。
 * 仅放行 http(s)://、mailto:、# 三类开头（大小写不敏感，前后空白容忍）；
 * 其余（javascript:/data:/vbscript:/相对路径等）一律返回 null——调用方将
 * 链接降级为纯文本渲染。方案白名单口径：非 http(s)/mailto/# 开头即纯文本
 * （相对路径亦不放行，属设计语义）。
 */
const SAFE_HREF_PATTERN = /^(?:https?:\/\/|mailto:|#)/i;

export function safeHref(href: string | undefined | null): string | null {
  if (!href) return null;
  const trimmed = href.trim();
  if (!trimmed) return null;
  return SAFE_HREF_PATTERN.test(trimmed) ? trimmed : null;
}
