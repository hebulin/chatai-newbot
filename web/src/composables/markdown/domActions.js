/** 将任意文本转换为可安全插入 HTML 的转义文本。 */
export function escapeHtml(value) {
  const element = document.createElement('div')
  element.textContent = value
  return element.innerHTML
}

/** 优先使用 Clipboard API，失败时回退到 execCommand。 */
export function copyTextWithFallback(text, onSuccess, onFailure) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(onSuccess).catch(() => {
      if (fallbackCopy(text)) onSuccess()
      else onFailure()
    })
    return
  }
  if (fallbackCopy(text)) onSuccess()
  else onFailure()
}

/** 使用隐藏 textarea 执行兼容复制。 */
function fallbackCopy(text) {
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const copied = document.execCommand('copy')
    document.body.removeChild(textarea)
    return copied
  } catch {
    return false
  }
}

/** 创建 Blob 并触发浏览器下载。 */
export function downloadFile(content, filename, type) {
  const blob = new Blob([content], { type })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  setTimeout(() => URL.revokeObjectURL(link.href), 1000)
}

/** 显示 Markdown 增强功能共用的轻量提示。 */
export function showToast(message) {
  let toast = document.getElementById('__md_toast')
  if (!toast) {
    toast = document.createElement('div')
    toast.id = '__md_toast'
    toast.style.cssText = 'position:fixed;left:50%;top:24px;transform:translateX(-50%);z-index:9999;'
      + 'padding:8px 16px;border-radius:6px;background:rgba(30,30,46,0.92);color:#eee;'
      + 'font-size:13px;box-shadow:0 4px 16px rgba(0,0,0,0.3);pointer-events:none;opacity:0;transition:opacity .2s'
    document.body.appendChild(toast)
  }
  toast.textContent = message
  requestAnimationFrame(() => { toast.style.opacity = '1' })
  clearTimeout(toast.__hideTimer)
  toast.__hideTimer = setTimeout(() => { toast.style.opacity = '0' }, 2500)
}
