import request from './request'

// 获取存储设置
export function getStorageSettings() {
  return request.get('/admin/settings/storage')
}

// 切换存储模式
export function setStorageMode(useSqlite) {
  return request.put('/admin/settings/storage', { useSqlite })
}

// 一键迁移
export function migrateData() {
  return request.post('/admin/settings/storage/migrate')
}

// 获取每日调用配额设置
export function getQuotaSettings() {
  return request.get('/admin/settings/quota')
}

// 设置配额/限流/上下文（参数为对象，0 表示不限制）
export function setQuotaSettings(payload) {
  return request.put('/admin/settings/quota', payload)
}
