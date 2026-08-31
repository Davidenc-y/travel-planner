import { defineConfig } from 'vitest/config';
import path from 'node:path';

// B0（front_design 06/PE-01）：纯函数单测，node 环境（无 DOM 依赖）；
// 组件/hook 测试后续批次按需引入 jsdom + @testing-library。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['__tests__/**/*.test.ts'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '.'),
    },
  },
});
