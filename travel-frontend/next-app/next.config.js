/** @type {import('next').NextConfig} */
// FE-S1b（20260912 前端专项）：connect-src 由环境推导（与 lib/api/http.ts 同源默认值），
// 覆盖部署态取值；默认端口（8081/8082/8083）与 R2/S2 硬编码版本逐字一致。
const planningBase = process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081';
const knowledgeBase = process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082';
// M6-34：STREAM_BASE 空串 = 运行时回退 planning 8081，但 CSP 需预放行灰度目标 8083
const streamBase = process.env.NEXT_PUBLIC_STREAM_BASE || 'http://localhost:8083';
const connectSrc = ["'self'", 'ws:', 'wss:', planningBase, knowledgeBase];
if (!connectSrc.includes(streamBase)) connectSrc.push(streamBase);

const securityHeaders = [
  { key: 'X-Frame-Options', value: 'DENY' },
  { key: 'X-Content-Type-Options', value: 'nosniff' },
  { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
  { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=()' },
  // R2/S2：CSP Report-Only 起步（只上报不阻断）；img 需放行 MinIO 代理/直连与外部降级图源，
  // connect 需放行 planning/knowledge/webflux 三后端（FE-S1b 起由上方环境推导）
  // FE-S1b 转强制条件：Report-Only 观察 ≥1 轮且浏览器 console 无意外违规报告、
  // 部署态 img-src/connect-src 域复核齐备后，将 key 换为 'Content-Security-Policy'
  // （2026-09-17 D-3c 已切换；unsafe-eval/unsafe-inline 保留属用户决策 C，后续再评估收紧）。
  {
    key: 'Content-Security-Policy',
    value: [
      "default-src 'self'",
      "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
      "style-src 'self' 'unsafe-inline'",
      "img-src 'self' data: blob: https: http://localhost:8082 http://192.168.253.129:9000",
      `connect-src ${connectSrc.join(' ')}`,
      "font-src 'self' data:",
      "object-src 'none'",
      "base-uri 'self'",
      // D-3b（E-19③）：违规上报指向 8081 collector（apiPlanning=既有 env 推导变量 planningBase 同名口径）
      `report-uri ${planningBase}/api/v1/csp-report`,
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
