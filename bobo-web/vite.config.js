import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

/**
 * 开发期前端跑在 5173，通过 proxy 把 /api 与 /upload 转发到本地后端 9080，
 * 这样开发和生产（nginx 同源反代）的请求路径完全一致，不需要在代码里区分环境。
 *
 * ------------------------------------------------------------------ base 路径
 * 站点支持部署在子路径下（例如 http://<host>/bobo-campus/）。
 * 由环境变量 VITE_BASE 控制，默认 '/'（开发期与根路径部署行为不变）：
 *   Docker 构建：docker-compose.yml 的 args.VITE_BASE 传进来（默认 /bobo-campus/）
 *   临时构建：  VITE_BASE=/foo/ pnpm build
 *
 * **为什么改这一处就够了**：base 会同时影响
 *   ① index.html 里引用的产物路径（/bobo-campus/assets/…）
 *   ② import.meta.env.BASE_URL（router 的 history base、http.js 的 baseURL 都读它）
 *   ③ public/ 下静态资源（logo、占位图）的引用位置
 * 因此**不要**再在业务代码里硬编码以 / 开头的资源路径，一律走 withBase()。
 * 注意 /api 与 /upload 仍挂在根路径：nginx 与后端都不需要跟着搬，
 * 子路径只影响「前端页面」这一层。
 */
export default defineConfig({
  base: process.env.VITE_BASE || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:9080',
        changeOrigin: true
      },
      '/upload': {
        target: 'http://localhost:9080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 把体积大的第三方库拆开，避免打成一个 2MB 的巨型 chunk
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          element: ['element-plus', '@element-plus/icons-vue'],
          echarts: ['echarts']
        }
      }
    }
  }
})
