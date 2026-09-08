/** 下载全部会话的纯文本、Markdown 或 JSON 归档。 */
export function downloadAllChats(format, { chats, chatMeta, folders, resolveTitle }) {
  if (format === 'json') {
    downloadChatsJson({ chats, chatMeta, folders })
    return
  }
  const content = format === 'md' ? buildAllChatsMarkdown(chats, resolveTitle) : buildAllChatsText(chats)
  const extension = format === 'md' ? 'md' : 'txt'
  const mime = format === 'md' ? 'text/markdown;charset=utf-8' : 'text/plain;charset=utf-8'
  downloadText(content, `chat-export-${today()}.${extension}`, mime)
}

/** 下载可重新导入的完整 JSON 会话备份。 */
export function downloadChatsJson({ chats, chatMeta, folders }) {
  const data = { app: 'chatai-newbot', version: 1, exportedAt: new Date().toISOString(), chats, chatMeta, folders }
  downloadText(JSON.stringify(data, null, 2), `chat-backup-${today()}.json`, 'application/json;charset=utf-8')
}

/** 校验备份并计算可导入会话、元信息与文件夹，不直接修改 Store。 */
export function prepareChatImport(data, { chats, chatSummaries, folders, normalizeFolders }) {
  if (!data || typeof data !== 'object' || !data.chats || typeof data.chats !== 'object') {
    throw new Error('备份文件格式不正确（缺少 chats 字段）')
  }
  const sourceMeta = data.chatMeta && typeof data.chatMeta === 'object' ? data.chatMeta : {}
  const chatEntries = []
  let skipped = 0
  Object.keys(data.chats).forEach(id => {
    const messages = data.chats[id]
    if (!Array.isArray(messages)) {
      skipped++
      return
    }
    if ((chats[id] && chats[id].length > 0) || (!chats[id] && (chatSummaries[id]?.count || 0) > 0)) {
      skipped++
      return
    }
    chatEntries.push({ id, messages, meta: sourceMeta[id] && typeof sourceMeta[id] === 'object' ? sourceMeta[id] : null })
  })
  const localIds = new Set(folders.map(folder => folder.id))
  const localNames = new Set(folders.map(folder => folder.name))
  const foldersToAdd = normalizeFolders(data.folders)
    .filter(folder => !localIds.has(folder.id) && !localNames.has(folder.name))
    .map(folder => ({ ...folder, collapsed: false }))
  return { chatEntries, foldersToAdd, imported: chatEntries.length, skipped }
}

/** 下载单个会话的 Markdown 文件。 */
export function downloadSingleChatMarkdown(messages, title) {
  const markdown = buildChatMarkdown(messages, title) + '\n'
  const safeTitle = title.replace(/[\\/:*?"<>|]/g, '_').substring(0, 40)
  downloadText(markdown, `${safeTitle}.md`, 'text/markdown;charset=utf-8')
}

/** 将全部会话转换为纯文本。 */
function buildAllChatsText(chats) {
  return Object.values(chats).map(messages => messages.map(message =>
    `[${message.time || ''}] ${message.role}: ${message.content}\n`
  ).join('')).join('\n====================\n\n')
}

/** 将全部有效会话转换为 Markdown。 */
function buildAllChatsMarkdown(chats, resolveTitle) {
  const sections = []
  Object.keys(chats).forEach(id => {
    const messages = chats[id] || []
    if (messages.some(message => message.role === 'user')) {
      sections.push(buildChatMarkdown(messages, resolveTitle(id)))
    }
  })
  return sections.join('\n\n---\n\n') + '\n'
}

/** 将一个会话转换为 Markdown。 */
function buildChatMarkdown(messages, title) {
  let markdown = `# ${title}\n\n`
  messages.forEach(message => {
    const roleLabel = message.role === 'user' ? '👤 用户' : '🤖 助手'
    markdown += `## ${roleLabel}${message.time ? `  \`${message.time}\`` : ''}\n\n`
    markdown += `${message.content || ''}\n\n`
  })
  return markdown.trimEnd()
}

/** 触发浏览器文本文件下载。 */
function downloadText(content, filename, type) {
  const blob = new Blob([content], { type })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename
  link.click()
  URL.revokeObjectURL(link.href)
}

/** 返回本地日期文件名片段。 */
function today() {
  return new Date().toISOString().slice(0, 10)
}
