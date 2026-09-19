import { NextResponse } from 'next/server';
import type { NextRequest } from 'next/server';

/**
 * 路由守卫（F91）：未登录访问受保护页 → /login。
 * 前端登录时由 auth-context 写入 accessToken cookie（F91 双写）。
 */
const PROTECTED = ['/itinerary', '/chat', '/profile', '/admin/reliability']; // M23（E1/P-C）：/plan 已收敛为 /chat 重定向

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

  // F-7a（P1-7）：nonce 生成 + 动态 CSP——script-src 剔除 'unsafe-inline' 改 'nonce-xxx'。
  // Next.js 14 自动将请求头 CSP 中的 nonce 注入其全部内联 <script>（框架原生支持），
  // 故 CSP 须同时设在请求头（供 Next 提取 nonce）与响应头（供浏览器执行）；
  // style-src 保留 'unsafe-inline'：Next.js CSS-in-JS/内联样式为框架级约束，移除会白屏。
  const nonce = crypto.randomUUID();
  const isProduction = process.env.NODE_ENV === 'production';
  const planningBase = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
  const knowledgeBase = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';
  const streamBase = process.env.NEXT_PUBLIC_STREAM_BASE || 'http://localhost:8083';
  const csp = [
    "default-src 'self'",
    `script-src 'self' 'nonce-${nonce}'${isProduction ? '' : " 'unsafe-eval'"}`,
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data: blob: https: http://localhost:8082 http://192.168.253.129:9000",
    `connect-src 'self' ws: wss: ${planningBase} ${knowledgeBase} ${streamBase}`,
    "font-src 'self' data:",
    "object-src 'none'",
    "base-uri 'self'",
    `report-uri ${planningBase}/api/v1/csp-report`,
  ].join('; ');

  const requestHeaders = new Headers(req.headers);
  requestHeaders.set('x-nonce', nonce); // 传递给 RSC/SSR 层（如需在组件内引用 nonce）
  requestHeaders.set('Content-Security-Policy', csp); // Next.js 从请求头 CSP 提取 nonce

  const response = NextResponse.next({ request: { headers: requestHeaders } });
  response.headers.set('Content-Security-Policy', csp);
  return response;
}

export const config = {
  // F-7a：matcher 从 5 条受保护路径扩为全站（未匹配路径将走 next.config.js 静态头仍含
  // unsafe-inline，全站化后动态头统一覆盖；静态资源除外以省边缘计算）
  matcher: ['/((?!_next/static|_next/image|favicon.ico).*)'],
};
