import request from './request'

// 获取当前用户的提示词预设列表（最多一条 enabled）
export function getPromptPresets() {
  return request.get('/user/prompt-presets')
}

// 保存提示词预设列表（服务端强制至多一条 enabled）
export function savePromptPresets(presets) {
  return request.put('/user/prompt-presets', { presets })
}
