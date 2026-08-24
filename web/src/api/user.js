import request from './request'

// 获取当前用户的提示词预设列表（最多一条 enabled）
export function getPromptPresets() {
  return request.get('/user/prompt-presets')
}

// 保存提示词预设列表（服务端强制至多一条 enabled）
export function savePromptPresets(presets) {
  return request.put('/user/prompt-presets', { presets })
}

// 获取当前用户个人资料与头像
export function getUserProfile() {
  return request.get('/user/profile')
}

// 保存当前用户个人资料与安全清理后的 SVG 头像源码
export function saveUserProfile(profile) {
  return request.put('/user/profile', profile)
}
