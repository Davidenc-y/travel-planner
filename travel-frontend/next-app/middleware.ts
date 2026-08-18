import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * 路由守卫（F91）：未登录访问受保护页 → /login。
 * 前端登录时由 auth-context 写入 accessToken cookie（F91 双写）。
 */
const PROTECTED = ['/plan', '/itinerary', '/chat', '/profile'];

function tokenExpired(token: string): boolean {
  try {
    const part = token.split('.')[1];
    if (!part) return false;
    const base64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const claims = JSON.parse(decodeURIComponent(escape(atob(base64)))) as { exp?: number };
    if (typeof claims.exp !== 'number') return false;
    // 提前 30 秒视为过期，与客户端 isTokenExpired 一致
    return Date.now() >= claims.exp * 1000 - 30_000;
  } catch {
    return false;
  }
}

export function middleware(req: NextRequest) {
  const token = req.cookies.get('accessToken')?.value;
  const { pathname } = req.nextUrl;
  const isProtected = PROTECTED.some((p) => pathname === p || pathname.startsWith(p + '/'));
  if (isProtected && !token) {
    const url = req.nextUrl.clone();
    url.pathname = '/login';
    url.searchParams.set('from', pathname);
    return NextResponse.redirect(url);
  }
  // F95：cookie 存在但已过期 → 清 cookie 并回首页（未登录态），带提示参数
  if (isProtected && token && tokenExpired(token)) {
    const url = req.nextUrl.clone();
    url.pathname = '/';
    url.search = '';
    url.searchParams.set('session', 'expired');
    const res = NextResponse.redirect(url);
    res.cookies.set('accessToken', '', { maxAge: 0, path: '/' });
    return res;
  }
  return NextResponse.next();
}

export const config = {
  matcher: ['/plan/:path*', '/itinerary/:path*', '/chat/:path*', '/profile/:path*'],
};
