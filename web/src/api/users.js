import request from './request'

// 分页查询用户（服务端分页，params: { page, size, username }）
export function getUsers(params) {
  return request.get('/admin/users', { params })
}

// 添加用户
export function addUser(data) {
  return request.post('/admin/users', data)
}

// 更新用户
export function updateUser(id, data) {
  return request.put(`/admin/users/${id}`, data)
}

// 删除用户
export function deleteUser(id) {
  return request.delete(`/admin/users/${id}`)
}

// 批量删除用户（内置管理员由后端保护，不会被删除）
export function batchDeleteUsers(ids) {
  return request.post('/admin/users/batch-delete', { ids })
}

// 更新用户权限
export function updateUserPermissions(id, allowedModelIds) {
  return request.put(`/admin/users/${id}/permissions`, { allowedModelIds })
}
