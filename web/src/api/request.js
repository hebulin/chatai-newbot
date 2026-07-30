import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 认证凭证存于 HttpOnly Cookie（同域请求自动携带），无需注入 Authorization 头

// 响应拦截器：处理 401/403
// - 401：登录失效（未登录/过期/账号禁用/IP变更）→ 清除登录态并跳转登录页
// - 403：仅表示无权限访问某接口（如普通用户误触管理员接口），只提示、不登出
let authRedirecting = false
request.interceptors.response.use(
  response => response.data,
  error => {
    const status = error.response?.status
    const reason = error.response?.headers?.['x-auth-reason']
    // 仅 401、或带鉴权原因头的响应视为会话失效，触发登出跳转
    if (status === 401 || (status === 403 && reason)) {
      if (!authRedirecting) {
        authRedirecting = true
        const msg = reason === 'ip_changed' ? '登录IP已变更，请重新登录'
          : reason === 'account_disabled' ? '账号已被禁用，请联系管理员'
          : '登录已过期，请重新登录'
        localStorage.removeItem('username')
        localStorage.removeItem('role')
        ElMessage.error(msg)
        setTimeout(() => {
          authRedirecting = false
          router.push('/login')
        }, 1500)
      }
      return Promise.reject(new Error('Auth failed'))
    }
    // 纯 403：无权限，仅提示不登出
    if (status === 403) {
      ElMessage.error(error.response?.data?.message || '无权限执行此操作')
      return Promise.reject(new Error('Forbidden'))
    }
    const msg = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
