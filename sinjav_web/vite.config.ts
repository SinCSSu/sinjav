import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 构建输出目录：SINJAV_WEB_OUT_DIR 环境变量可覆盖（如静态托管目录 /var/www/sinvaj），默认 dist/
const outDir = process.env.SINJAV_WEB_OUT_DIR || 'dist'

export default defineConfig({
  plugins: [vue()],
  build: {
    outDir,
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8656',
        changeOrigin: true,
      },
      '/local-images': {
        target: 'http://localhost:8656',
        changeOrigin: true,
      },
      // 演员头像静态资源（后端把 sinjav.actors.photo-dir 映射到 /local-actors/**）
      '/local-actors': {
        target: 'http://localhost:8656',
        changeOrigin: true,
      },
    },
  },
})
