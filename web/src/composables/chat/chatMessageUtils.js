/**
 * 生成聊天消息展示时间，保持与既有中文本地化格式一致。
 */
export function formatChatTime() {
  return new Date().toLocaleString('zh-CN')
}

/**
 * 按前后端约定的保守规则估算文本 Token 数量。
 */
export function estimateChatTokens(text) {
  if (!text) return 0
  let cjk = 0
  let ascii = 0
  let other = 0
  for (const ch of String(text)) {
    const code = ch.codePointAt(0)
    if (code < 128) ascii++
    else if ((code >= 0x3000 && code <= 0x9FFF) || (code >= 0xFF00 && code <= 0xFFEF)) cjk++
    else other++
  }
  return Math.ceil((cjk + ascii / 4 + other / 2) * 1.15)
}
