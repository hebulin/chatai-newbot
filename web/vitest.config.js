// Vitest 配置：jsdom 环境（DOMPurify/marked 依赖 DOM），@ 别名与 vite.config.js 对齐
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.js']
  }
})
