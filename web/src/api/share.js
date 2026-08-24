import request from './request'

// 获取我创建的分享列表（含失效状态 status: valid/expired/orphaned）
export function getMyShares() {
  return request.get('/share')
}

// 批量删除我自己的分享（清除失效/批量撤销）
export function batchDeleteMyShares(ids) {
  return request.post('/share/batch-delete', { ids })
}

// 为指定会话创建分享（同一会话复用已有分享码）
// expireDays：<=0 或缺省=永久有效
export function createShare(chatId, options = {}) {
  const payload = typeof options === 'number' ? { expireDays: options } : options
  return request.post('/share', { chatId, ...payload })
}

// 撤销分享
export function deleteShare(id) {
  return request.delete(`/share/${id}`)
}

// 匿名查看分享内容（无需登录）
export function getSharedChat(id, password = '') {
  return password
    ? request.post(`/share/view/${id}`, { password })
    : request.get(`/share/view/${id}`)
}

// 把分享快照复制到当前登录用户的会话列表
export function cloneSharedChat(id, password = '') {
  return request.post(`/share/${id}/clone`, { password })
}

// ===== 后台管理（仅管理员） =====

// 分页查询全部用户的分享记录（服务端筛选+分页，params: { page, size, username, status }）
// 额外返回 invalidCount（全量失效条数，供「清除失效」按钮用）
export function getAdminShares(params) {
  return request.get('/admin/shares', { params })
}

// 批量删除分享（批量清除失效/批量撤销）
export function batchDeleteShares(ids) {
  return request.post('/admin/shares/batch-delete', { ids })
}

// 一键清除全部失效分享（失效判定在服务端完成）
export function deleteInvalidShares() {
  return request.post('/admin/shares/delete-invalid')
}
