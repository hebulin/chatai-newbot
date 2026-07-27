import request from './request'

// 获取所有用户
export function getUsers() {
  return request.get('/admin/users')
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

// 更新用户权限
export function updateUserPermissions(id, allowedModelIds) {
  return request.put(`/admin/users/${id}/permissions`, { allowedModelIds })
}
