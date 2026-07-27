import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getMe } from '@/api/auth'

// 安全访问 localStorage（隐私模式/禁用存储时返回 null 或忽略错误）
function safeGet(key) {
  try { return localStorage.getItem(key) } catch (e) { return null }
}
function safeSet(key, val) {
  try { localStorage.setItem(key, val) } catch (e) { /* ignore */ }
}
function safeRemove(key) {
  try { localStorage.removeItem(key) } catch (e) { /* ignore */ }
}

export const useAuthStore = defineStore('auth', () => {
  const username = ref(safeGet('username') || '')
  const role = ref(safeGet('role') || '')
  const userId = ref('')
  const isLoggedIn = ref(!!safeGet('token'))

  // 校验登录态并同步 store
  async function checkAuth() {
    const token = safeGet('token')
    if (!token) {
      isLoggedIn.value = false
      return false
    }
    try {
      const res = await getMe()
      if (res && res.success) {
        username.value = res.username
        role.value = res.role
        userId.value = res.id
        isLoggedIn.value = true
        safeSet('username', res.username)
        safeSet('role', res.role)
        return true
      }
    } catch (e) {
      // 401 会被拦截器处理
    }
    isLoggedIn.value = false
    return false
  }

  // 写入登录态
  function setAuth(data) {
    safeSet('token', data.token)
    safeSet('username', data.username)
    safeSet('role', data.role)
    username.value = data.username
    role.value = data.role
    isLoggedIn.value = true
  }

  // 登出并清空本地态
  function logout() {
    safeRemove('token')
    safeRemove('username')
    safeRemove('role')
    isLoggedIn.value = false
    username.value = ''
    role.value = ''
    userId.value = ''
  }

  return { username, role, userId, isLoggedIn, checkAuth, setAuth, logout, safeGet, safeSet, safeRemove }
})
