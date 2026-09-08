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
  test: {
    environment: 'jsdom',
    include: ['tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
  },
}));
