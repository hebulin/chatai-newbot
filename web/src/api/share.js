import request from './request'

// 获取我创建的分享列表
export function getMyShares() {
  return request.get('/share')
}

// 为指定会话创建分享（同一会话复用已有分享码）
// expireDays：<=0 或缺省=永久有效
export function createShare(chatId, expireDays) {
  return request.post('/share', { chatId, expireDays })
}

// 撤销分享
export function deleteShare(id) {
  return request.delete(`/share/${id}`)
}

// 匿名查看分享内容（无需登录）
export function getSharedChat(id) {
  return request.get(`/share/view/${id}`)
}
