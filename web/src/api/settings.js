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

// 获取联网搜索设置（Key 仅返回掩码）
export function getWebSearchSettings() {
  return request.get('/admin/settings/websearch')
}

// 保存联网搜索设置（apiKey 为空或掩码时保留原 Key）
export function setWebSearchSettings(payload) {
  return request.put('/admin/settings/websearch', payload)
}

// Tavily 连通性测试（apiKey 可空，空时用已保存的 Key）
export function testWebSearch(apiKey) {
  return request.post('/admin/settings/websearch/test', { apiKey }, { timeout: 30000 })
}

// 获取公告设置（admin）
export function getAnnouncementSettings() {
  return request.get('/admin/settings/announcement')
}

// 保存公告设置（content 传空则清除公告）
export function setAnnouncementSettings(content) {
  return request.put('/admin/settings/announcement', { content })
}
