import request from './request'

// 获取模型列表
export function fetchModels() {
  return request.get('/models')
}

// 获取会话历史（全量，含消息内容；仅导出备份等场景使用）
export function loadChatHistory() {
  return request.get('/chat/history')
}

// 获取会话摘要列表（仅标题/预览/时间/条数，懒加载模式的首屏拉取）
export function loadChatSummaries() {
  return request.get('/chat/history/summary')
}

// 保存会话历史
export function saveChatHistory(data) {
  return request.post('/chat/history', data)
}

// 获取单个会话的最新记录（发送前同步当前会话，避免拉全量）
// config 可传入 axios 配置（如 signal），用于快速切换会话时中断陈旧请求
export function loadSingleChatHistory(chatId, config) {
  return request.get('/chat/history/single', { params: { chatId }, ...config })
}

// 跨会话全文搜索
export function searchChatHistory(q) {
  return request.get('/chat/history/search', { params: { q } })
}

// AI 自动命名会话（生成失败时后端返回 success=false，前端自行回退）
export function generateChatTitle(modelConfigId, userContent, assistantContent) {
  return request.post('/chat/generate-title', { modelConfigId, userContent, assistantContent })
}

// 获取系统公告（登录用户可见）
export function fetchAnnouncement() {
  return request.get('/announcement')
}

// 上传聊天图片（返回服务端文件 URL，替代 base64 内嵌）
export function uploadChatImage(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/upload/image', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 上传聊天附件文档（服务端解析为纯文本后落盘，返回引用 URL 与字数，不依赖模型多模态）
export function uploadChatDocument(file) {
  const formData = new FormData()
  formData.append('file', file)
  return request.post('/upload/document', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
