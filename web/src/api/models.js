import request from './request'

// 获取所有模型
export function getModels() {
  return request.get('/admin/models')
}

// 添加模型
export function addModel(data) {
  return request.post('/admin/models', data)
}

// 更新模型
export function updateModel(id, data) {
  return request.put(`/admin/models/${id}`, data)
}

// 删除模型
export function deleteModel(id) {
  return request.delete(`/admin/models/${id}`)
}

// 模型连通性测试（手动触发：连通验证 + 测速两次请求，超时适当放宽）
export function testModel(id) {
  return request.post(`/admin/models/${id}/test`, null, { timeout: 60000 })
}

// 批量快速接入
export function batchAddModels(data) {
  return request.post('/admin/models/batch', data)
}

// 设置默认模型
export function setDefaultModel(modelId) {
  return request.put('/admin/models/default', { modelId })
}

// 取消默认模型
export function clearDefaultModel() {
  return request.delete('/admin/models/default')
}
