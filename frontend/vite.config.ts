import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 构建产物输出到后端资源的 static 目录：Spring-less 的 JDK HttpServer 直接按 classpath
// 读取 /static/index.html 与 /static/assets/*，无需任何前端服务器。
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets',
    sourcemap: false,
  },
  server: {
    // 本地开发时把 /api 代理到已运行的后端（8077 是 dev profile 端口，按需调整）
    proxy: {
      '/api': 'http://127.0.0.1:8097',
    },
  },
});
