import { defineConfig } from '@playwright/test';

/**
 * 端到端验证以构建产物（vite preview）运行真实浏览器，
 * 并用路由级 API 仿真替代后端：会话令牌、页面、引用、
 * 来源预览与聊天会话都由确定性夹具提供，不依赖外部服务。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  use: {
    baseURL: 'http://localhost:4173',
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run build && npm run preview -- --port 4173 --strictPort',
    port: 4173,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],
});
