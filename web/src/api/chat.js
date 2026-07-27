import request from './request'

// 获取模型列表
export function fetchModels() {
  return request.get('/models')
}

// 获取会话历史
export function loadChatHistory() {
  return request.get('/chat/history')
}

// 保存会话历史
export function saveChatHistory(data) {
  return request.post('/chat/history', data)
}
