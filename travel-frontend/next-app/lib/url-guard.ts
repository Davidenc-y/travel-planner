/**
 * URL 展示层加密（F96）：仿 Bing 搜索 URL 形态的"参数值不可直读"格式。
 *
 * 例：/itinerary?itineraryId=<加密令牌>
 *  - 参数名保持原样（itineraryId），仅"值"经 XOR+hex 混淆并加随机盐（不可直读）；
 *  - 页面解码还原真实 id 后，仍调用后端原始接口（后端无感知）。
 *
 * 注意：这是展示层混淆（防肉眼/防直读），非安全加密；正式敏感场景应换服务端加密。
 */

const KEY = 'travel-planner-url-guard-2026';

function randomHex(len: number): string {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID().replace(/-/g, '').slice(0, len);
  }
  return 'a'.repeat(len);
}

/** 行程 id → 不可直读令牌（id:盐 逐字符 XOR 后转 hex） */
export function encodeItineraryId(id: number | string): string {
  const text = `${id}:${randomHex(8)}`;
  let out = '';
  for (let i = 0; i < text.length; i++) {
    out += (text.charCodeAt(i) ^ KEY.charCodeAt(i % KEY.length))
      .toString(16)
      .padStart(2, '0');
  }
  return out;
}

/** 令牌 → 原始行程 id（解码失败返回空串） */
export function decodeItineraryId(token: string): string {
  try {
    let text = '';
    for (let i = 0; i + 1 < token.length; i += 2) {
      const code = parseInt(token.slice(i, i + 2), 16)
        ^ KEY.charCodeAt((i / 2) % KEY.length);
      text += String.fromCharCode(code);
    }
    const colon = text.indexOf(':');
    return colon > 0 ? text.slice(0, colon) : text;
  } catch {
    return '';
  }
}

/** 构造行程详情展示 URL：参数名不变，值加密不可直读 */
export function buildItineraryUrl(id: number | string): string {
  return `/itinerary?itineraryId=${encodeItineraryId(id)}`;
}
