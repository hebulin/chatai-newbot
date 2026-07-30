import request from './request'

// 分页查询审计日志（管理员）
export function getAuditLogs(params) {
  return request.get('/admin/audit-logs', { params })
}

// 获取已出现过的操作类型列表（筛选下拉用）
export function getAuditActions() {
  return request.get('/admin/audit-logs/actions')
}

// 重置（清空）全部审计日志，需管理员密码；重置后仅保留一条重置记录
export function resetAuditLogs(password) {
  return request.post('/admin/audit-logs/reset', { password })
}
