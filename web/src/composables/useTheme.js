import { ref } from 'vue'
import { setGlobalTheme } from '@/composables/useMarkdown'

const THEME_KEY = 'ai-chat-theme'
// 用户是否手动选择过主题：未手动选择时跟随系统明暗（prefers-color-scheme），
// 一旦用户点击切换按钮则持久化其选择，之后不再跟随系统
const userChosen = !!localStorage.getItem(THEME_KEY)
const systemDark = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null
const theme = ref(localStorage.getItem(THEME_KEY) || (systemDark && !systemDark.matches ? 'light' : 'dark'))

export function useTheme() {
  /** 初始化主题：应用存储的主题（或系统偏好），并同步给 Markdown 渲染层（mermaid 明暗映射） */
  function initTheme() {
    applyTheme(theme.value, { persist: false })
    // 未手动选择过主题时，监听系统明暗变化实时跟随
    if (!userChosen && systemDark) {
      const onSystemChange = (e) => {
        // 监听期间用户一旦手动切换，localStorage 有值后即停止跟随
        if (localStorage.getItem(THEME_KEY)) {
          systemDark.removeEventListener('change', onSystemChange)
          return
        }
        applyTheme(e.matches ? 'dark' : 'light', { persist: false })
      }
      systemDark.addEventListener('change', onSystemChange)
    }
  }

  /** 应用主题：写 data-theme 属性、按需持久化、切换 Element Plus dark class，并同步渲染层明暗 */
  function applyTheme(t, { persist = true } = {}) {
    const val = t === 'light' ? 'light' : 'dark'
    theme.value = val
    document.documentElement.setAttribute('data-theme', val)
    if (persist) localStorage.setItem(THEME_KEY, val)
    // 同步明暗标记到 Markdown 渲染层（mermaid 默认主题映射与图表缓存键依赖它）
    setGlobalTheme(val)
    // Element Plus dark mode
    if (val === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  /** 切换深浅主题（用户手动选择，持久化后不再跟随系统） */
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
