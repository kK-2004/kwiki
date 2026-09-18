import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
export default defineConfig({
  base: "/admin/", plugins: [vue()],
  build: { outDir: "dist", emptyOutDir: true },
  server: { proxy: { "/api": "http://localhost:8080" } },
  test: { environment: "jsdom", setupFiles: ["./tests/setup.ts"] }
});
