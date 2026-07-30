import { ref } from 'vue'

const THEME_KEY = 'ai-chat-theme'
const theme = ref(localStorage.getItem(THEME_KEY) || 'dark')

export function useTheme() {
  function initTheme() {
    applyTheme(theme.value)
  }

  function applyTheme(t) {
    const val = t === 'light' ? 'light' : 'dark'
    theme.value = val
    document.documentElement.setAttribute('data-theme', val)
    localStorage.setItem(THEME_KEY, val)
    // Element Plus dark mode
    if (val === 'dark') {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  }

  function toggleTheme() {
    const next = theme.value === 'dark' ? 'light' : 'dark'
    applyTheme(next)
  }

  function getTheme() {
    return theme.value
  }

  return { theme, initTheme, applyTheme, toggleTheme, getTheme }
}
