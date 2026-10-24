import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 使用 @ 指向 src，方便 import
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      // 开发环境：/api 代理到后端 http://localhost:8080/api，避免跨域
      '/api': {
        target: 'http://localhost:8080/api',
        changeOrigin: true
      },
      // 开发环境：/agent 代理到 Text-to-SQL Agent 服务 http://localhost:8000（与 /api 对称）
      // rewrite 去掉 /agent 前缀：/agent/chat -> http://localhost:8000/chat（agent 路由本身无前缀）
      '/agent': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/agent/, '')
      }
    }
  },
  build: {
    // 部署到 nginx html 根目录，使用相对/绝对根路径均可；此处用根路径
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        // 拆分体积较大的第三方库，便于浏览器缓存
        manualChunks: {
          vue: ['vue', 'pinia'],
          'element-plus': ['element-plus'],
          echarts: ['echarts'],
          axios: ['axios']
        }
      }
    }
  }
})
