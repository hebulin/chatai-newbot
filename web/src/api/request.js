import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

const request = axios.create({
  baseURL: '/api',
  timeout: 30000
})

// 请求拦截器：注入 token
request.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：处理 401/403
let authRedirecting = false
request.interceptors.response.use(
  response => response.data,
  error => {
    const status = error.response?.status
    if (status === 401 || status === 403) {
      if (!authRedirecting) {
        authRedirecting = true
        const reason = error.response?.headers?.['x-auth-reason']
        const msg = reason === 'ip_changed' ? '登录IP已变更，请重新登录'
          : reason === 'account_disabled' ? '账号已被禁用，请联系管理员'
          : '登录已过期，请重新登录'
        localStorage.removeItem('token')
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
    const msg = error.response?.data?.message || error.message || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default request
