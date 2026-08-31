/** @type {import('next').NextConfig} */
const securityHeaders = [
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
  // R2/S2：CSP Report-Only 起步（只上报不阻断）；img 需放行 MinIO 代理/直连与外部降级图源，
  // connect 需放行 planning/knowledge/webflux 三后端
  {
    key: 'Content-Security-Policy-Report-Only',
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https: http://localhost:8082 http://192.168.253.129:9000",
      "connect-src 'self' ws: wss: http://localhost:8081 http://localhost:8082 http://localhost:8083",
      "font-src 'self' data:",
      "object-src 'none'",
      "base-uri 'self'",
    ].join('; '),
  },
];

const nextConfig = {
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_API_PLANNING: process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081',
    NEXT_PUBLIC_API_KNOWLEDGE: process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082',
    // M6-34：聊天 SSE 灰度目标（空 = 回退 planning 8081）
    NEXT_PUBLIC_STREAM_BASE: process.env.NEXT_PUBLIC_STREAM_BASE || '',
  },
  async headers() {
    return [{ source: '/:path*', headers: securityHeaders }];
  },
};

module.exports = nextConfig;
