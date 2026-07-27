import request from './request'

// 获取全局提示词
export function getSystemPrompt() {
  return request.get('/user/system-prompt')
}

// 保存全局提示词
export function saveSystemPrompt(systemPrompt) {
  return request.put('/user/system-prompt', { systemPrompt })
}
