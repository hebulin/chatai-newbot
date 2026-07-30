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

// 获取安全设置（IP绑定校验开关）
export function getSecuritySettings() {
  return request.get('/admin/settings/security')
}

// 保存安全设置（payload: { ipBindingEnabled }）
export function setSecuritySettings(payload) {
  return request.put('/admin/settings/security', payload)
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

// 获取全部公告列表（含历史公告，admin）
export function listAnnouncements() {
  return request.get('/admin/announcements')
}

// 发布新公告（payload: { content, startAt, endAt }，公告期可空）
export function publishAnnouncement(payload) {
  return request.post('/admin/announcements', payload)
}

// 重新生效/更改公告期（payload: { content, startAt, endAt }，content 空=不修改）
export function republishAnnouncement(id, payload) {
  return request.put(`/admin/announcements/${id}`, payload)
}

// 下线公告（保留历史记录，可重新生效）
export function offlineAnnouncement(id) {
  return request.put(`/admin/announcements/${id}/offline`)
}

// 删除公告记录
export function deleteAnnouncement(id) {
  return request.delete(`/admin/announcements/${id}`)
}
