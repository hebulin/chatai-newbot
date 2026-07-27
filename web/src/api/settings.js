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
