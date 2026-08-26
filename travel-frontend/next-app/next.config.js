/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_API_PLANNING: process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081',
    NEXT_PUBLIC_API_KNOWLEDGE: process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082',
    // M6-34：聊天 SSE 灰度目标（空 = 回退 planning 8081）
    NEXT_PUBLIC_STREAM_BASE: process.env.NEXT_PUBLIC_STREAM_BASE || '',
  },
};

module.exports = nextConfig;
