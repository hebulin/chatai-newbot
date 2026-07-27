import request from './request'

// 获取使用记录（分页）
export function getUsageLogs(params) {
  return request.get('/usage', { params })
}

// 获取用户统计（分页）
export function getUserStats(params) {
  return request.get('/usage/stats', { params })
}

// 获取汇总数据
export function getUsageSummary(params) {
  return request.get('/usage/summary', { params })
}

// 获取用户名列表（管理员用）
export function getUsernames() {
  return request.get('/usage/filters')
}
