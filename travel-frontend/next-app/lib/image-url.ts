/**
 * F121：MinIO 图片 URL 解析（前端统一入口）。
 *
 * 规则：
 *  - 命中 NEXT_PUBLIC_MINIO_ENDPOINT 的完整 URL → 解析 bucket/object →
 *    按 NEXT_PUBLIC_MINIO_ACCESS_MODE 重写为代理/直连 URL；
 *  - presign 模式返回 presign 接口地址，由 SmartImage 异步换取签名 URL；
 *  - 其他 http(s)（AMap 原图等外部降级 URL）→ 原样直连；
 *  - 相对 bucket/object（内部）→ 同样按模式重写。
 */

const MINIO_ENDPOINT = process.env.NEXT_PUBLIC_MINIO_ENDPOINT || 'http://192.168.253.129:9000';
const ACCESS_MODE = process.env.NEXT_PUBLIC_MINIO_ACCESS_MODE || 'proxy';
const KNOWLEDGE_BASE = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';

export function resolveImageSrc(src?: string | null): string | null {
  if (!src) return null;
  const s = src.trim();
  if (!s) return null;

  if (s.startsWith('http://') || s.startsWith('https://')) {
    try {
      const u = new URL(s);
      const minio = new URL(MINIO_ENDPOINT);
      if (u.host === minio.host) {
        const parts = u.pathname.split('/').filter(Boolean);
        if (parts.length === 2) {
          return buildAccessible(parts[0], parts[1]);
        }
      }
    } catch {
      // 非法 URL 原样返回，交给 <img> onError 兜底
    }
    return s;
  }

  const parts = s.split('/').filter(Boolean);
  if (parts.length === 2) {
    return buildAccessible(parts[0], parts[1]);
  }
  return s;
}

function buildAccessible(bucket: string, object: string): string {
  const b = encodeURIComponent(bucket);
  const o = encodeURIComponent(object);
  if (ACCESS_MODE === 'direct') {
    return `${MINIO_ENDPOINT}/${bucket}/${object}`;
  }
  if (ACCESS_MODE === 'presign') {
    return `${KNOWLEDGE_BASE}/api/v1/files/presign?bucket=${b}&object=${o}`;
  }
  return `${KNOWLEDGE_BASE}/api/v1/files/proxy?bucket=${b}&object=${o}`;
}
