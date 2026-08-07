import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 模板组件按需自动导入（含对应样式），替代全量引入以减小首屏包体
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false
    })
  ],
  base: '/',
  // 生产构建移除调试输出：console.log/info/debug 为开发噪音，console.warn/error 保留
  // 用于线上问题排查（pure 标记为无副作用函数，被 tree-shaking 移除）
  esbuild: {
    pure: ['console.log', 'console.info', 'console.debug']
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9092',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      output: {
        // 稳定的基础 vendor 单独成块提升缓存命中率；Element Plus 不强制合块，
        // 由 Rollup 按使用自然拆分，后台专用组件（el-table 等）随路由懒加载
        manualChunks(id) {
          if (!id.includes('node_modules')) return undefined
          if (/node_modules[\\/](vue|@vue|vue-router|pinia)[\\/]/.test(id)) return 'vue-vendor'
          return undefined
        }
      }
    }
  }
})
