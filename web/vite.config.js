import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { bundleBudgetPlugin } from './build/bundleBudget.js'

/**
 * 为明确的重型导出功能指定稳定分块；Mermaid 自身的动态导入继续按图表类型自然分块。
 */
function featureChunkName(id) {
  const normalized = id.replace(/\\/g, '/')
  if (normalized.includes('/node_modules/xlsx/')) return 'xlsx-export'
  if (/node_modules\/(vue|@vue|vue-router|pinia)\//.test(normalized)) return 'vue-vendor'
  return undefined
}

/** 仅命名 Mermaid 的独立图表块，不改变其动态依赖归属。 */
function diagramChunkFileName(chunk) {
  if (/^flowchart-elk-definition/.test(chunk.name)) return 'assets/mermaid-elk-[hash].js'
  if (/^mindmap-definition/.test(chunk.name)) return 'assets/mermaid-mindmap-[hash].js'
  if (/Diagram|Diagram-v2|timeline-definition/.test(chunk.name)) {
    const diagram = chunk.name.replace(/-[a-f0-9]{8}$/, '')
    return `assets/mermaid-${diagram}-[hash].js`
  }
  return 'assets/[name]-[hash].js'
}

export default defineConfig({
  plugins: [
    vue(),
    // Element Plus 模板组件按需自动导入（含对应样式），替代全量引入以减小首屏包体
    Components({
      resolvers: [ElementPlusResolver()],
      dts: false
    }),
    bundleBudgetPlugin()
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
        chunkFileNames: diagramChunkFileName,
        // 稳定的基础 vendor 单独成块提升缓存命中率；Element Plus 不强制合块，
        // 由 Rollup 按使用自然拆分，后台专用组件（el-table 等）随路由懒加载
        manualChunks(id) {
          return featureChunkName(id)
        }
      }
    }
  }
})
