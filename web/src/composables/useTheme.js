import { ref } from 'vue'
import { setGlobalTheme } from '@/composables/useMarkdown'

const THEME_KEY = 'ai-chat-theme'
const theme = ref(localStorage.getItem(THEME_KEY) || 'dark')

export function useTheme() {
  /** 初始化主题：应用存储的主题，并同步给 Markdown 渲染层（mermaid 明暗映射） */
  function initTheme() {
    applyTheme(theme.value)
  }

  /** 应用主题：写 data-theme 属性、持久化、切换 Element Plus dark class，并同步渲染层明暗 */
  function applyTheme(t) {
    const val = t === 'light' ? 'light' : 'dark'
    theme.value = val
    document.documentElement.setAttribute('data-theme', val)
    localStorage.setItem(THEME_KEY, val)
    // 同步明暗标记到 Markdown 渲染层（mermaid 默认主题映射与图表缓存键依赖它）
    setGlobalTheme(val)
    // Element Plus dark mode
    if (val === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  /** 切换深浅主题 */
  function toggleTheme() {
    const next = theme.value === 'dark' ? 'light' : 'dark'
    applyTheme(next)
  }

  /** 获取当前主题值（'dark' | 'light'） */
  function getTheme() {
    return theme.value
  }

  return { theme, initTheme, applyTheme, toggleTheme, getTheme }
}
