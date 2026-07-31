/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: true,
  env: {
    NEXT_PUBLIC_API_PLANNING: process.env.NEXT_PUBLIC_API_PLANNING || 'http://localhost:8081',
    NEXT_PUBLIC_API_KNOWLEDGE: process.env.NEXT_PUBLIC_API_KNOWLEDGE || 'http://localhost:8082',
  },
};

module.exports = nextConfig;
