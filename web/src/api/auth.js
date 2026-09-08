import request from './request'

// 获取当前用户信息
export function getMe() {
  return request.get('/auth/me')
}

// 登录
export function login(data) {
  return request.post('/auth/login', data)
}

// 注册
export function register(data) {
  return request.post('/auth/register', data)
}

// 获取注册总开关、邀请码要求与一次性验证码挑战
export function getRegisterConfig() {
  return request.get('/auth/register-config')
}

// 退出登录
export function logout() {
  return request.post('/auth/logout')
}

// 修改密码
export function changePassword(data) {
  return request.post('/auth/change-password', data)
}

// 获取当前账号的登录设备列表
export function getSessions() {
  return request.get('/auth/sessions')
}

// 踢掉指定登录设备
export function kickSession(sessionId) {
  return request.delete(`/auth/sessions/${sessionId}`)
}

// 完成登录阶段的 TOTP 或恢复码验证
export function verifyTwoFactorLogin(data) {
  return request.post('/auth/2fa/verify-login', data)
}

// 查询当前账号的双重验证状态
export function getTwoFactorStatus() {
  return request.get('/auth/2fa/status')
}

// 验证当前密码并创建扫码绑定挑战
export function setupTwoFactor(data) {
  return request.post('/auth/2fa/setup', data)
}

// 校验首次 TOTP 并正式启用双重验证
export function enableTwoFactor(data) {
  return request.post('/auth/2fa/enable', data)
}

// 验证当前密码并关闭双重验证
export function disableTwoFactor(data) {
  return request.post('/auth/2fa/disable', data)
}

// 验证密码与 TOTP 后重新生成恢复码
export function regenerateRecoveryCodes(data) {
  return request.post('/auth/2fa/recovery-codes/regenerate', data)
}
