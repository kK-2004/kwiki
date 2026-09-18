import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import UnoCSS from '@unocss/vite';

export default defineConfig(({ mode }) => ({
  plugins: [vue(), UnoCSS()],
  server: {
    proxy: {
      '/api': loadEnv(mode, '.', '').VITE_API_TARGET || 'http://localhost:8080',
    },
  },
  build: {
    // PDF/DOCX 预览适配器只经动态 import 进入：独立分包确保
    // 它们（以及 pdf worker）绝不落入阅读器首包。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('pdfjs-dist')) return 'pdfjs';
          if (id.includes('docx-preview') || id.includes('jszip')) return 'docx';
        },
      },
    },
  },
  worker: { format: 'es' as const },
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
  },
}));
