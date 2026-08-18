/**
 * JWT 过期校验（F95）：客户端解析 accessToken 的 exp，供"进入页面先校验再放行"。
 */

interface JwtClaims {
  exp?: number;
}

function parseJwt(token: string): JwtClaims | null {
  try {
    const part = token.split('.')[1];
    if (!part) return null;
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const json = decodeURIComponent(escape(atob(base64)));
    return JSON.parse(json) as JwtClaims;
  } catch {
    return null;
  }
}

/**
 * 是否已过期：exp 缺失/无法解析按"未过期"处理（避免误杀正常 token）。
 * 提前 30 秒视为过期（网络/时钟偏差缓冲）。
 */
export function isTokenExpired(token: string | null | undefined): boolean {
  if (!token) return true;
  const claims = parseJwt(token);
  if (!claims || typeof claims.exp !== 'number') return false;
  return Date.now() >= claims.exp * 1000 - 30_000;
}
