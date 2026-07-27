import request from './request'

// 获取所有厂商（预置+自定义）
export function getProviders() {
  return request.get('/admin/providers')
}

// 修改厂商显示名/图标
export function renameProvider(providerId, data) {
  return request.patch(`/admin/providers/${encodeURIComponent(providerId)}`, data)
}
