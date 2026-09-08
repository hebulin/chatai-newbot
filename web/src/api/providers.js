import request from './request'

// 获取所有厂商（预置+自定义）
export function getProviders() {
  return request.get('/admin/providers')
}

// 修改厂商显示名/图标
export function renameProvider(providerId, data) {
  return request.patch(`/admin/providers/${encodeURIComponent(providerId)}`, data)
}

// 从厂商上游 /models 接口获取最新模型目录
export function fetchProviderModels(providerId, data = {}) {
  return request.post(`/admin/providers/${encodeURIComponent(providerId)}/fetch-models`, data, { timeout: 30000 })
}

// 保存厂商支持模型目录，快速接入和添加模型会立即使用新列表
export function saveProviderModels(providerId, models) {
  return request.put(`/admin/providers/${encodeURIComponent(providerId)}/models`, { models })
}
