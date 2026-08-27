import request from './request'

// ===== 系统备份与恢复（仅管理员） =====

// 获取备份列表与定时备份开关
export function listBackups() {
  return request.get('/admin/backups')
}

// 立即创建完整备份
export function createBackup() {
  return request.post('/admin/backups')
}

// 下载备份文件（返回 blob URL 供 a 标签下载）
export function downloadBackup(name) {
  return request.get(`/admin/backups/download/${encodeURIComponent(name)}`, { responseType: 'blob' })
}

// 删除备份文件
export function deleteBackup(name) {
  return request.delete(`/admin/backups/${encodeURIComponent(name)}`)
}

// 从服务器备份恢复（需管理员密码重新认证）
export function restoreBackup(name, password) {
  return request.post('/admin/backups/restore', { name, password })
}

// 上传备份文件并恢复（需管理员密码重新认证）
export function restoreBackupUpload(file, password) {
  const formData = new FormData()
  formData.append('file', file)
  formData.append('password', password)
  return request.post('/admin/backups/restore-upload', formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

// 设置定时备份开关
export function setBackupAutoEnabled(enabled) {
  return request.put('/admin/backups/settings', { enabled })
}
