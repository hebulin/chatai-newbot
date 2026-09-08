/**
 * 将会话正文、摘要和元信息投影为侧边栏分组结构。
 */
export function buildChatListProjection({ chats, chatSummaries, chatMeta, folders, searchKeyword, now = new Date() }) {
  const chatInfos = []
  const ids = new Set([...Object.keys(chats), ...Object.keys(chatSummaries)])
  ids.forEach(id => {
    const meta = chatMeta[id] || {}
    let autoTitle
    let fullContent
    let lastTime
    if (chats[id] !== undefined) {
      const messages = chats[id] || []
      let first = null
      lastTime = null
      for (let index = 0; index < messages.length; index++) {
        if (messages[index].role === 'user' && !first) first = messages[index]
        if (messages[index].time) lastTime = messages[index].time
      }
      if (!first) return
      autoTitle = first.content.substring(0, 20)
      fullContent = first.content
    } else {
      const summary = chatSummaries[id] || {}
      if ((summary.count || 0) === 0) return
      autoTitle = summary.title || '新会话'
      fullContent = summary.preview || ''
      lastTime = summary.lastTime || null
    }
    const title = meta.title?.trim() ? meta.title : autoTitle
    chatInfos.push({
      id,
      title,
      pinned: !!meta.pinned,
      folderId: meta.folderId || null,
      fullContent,
      lastTime,
      lastTimeDate: parseChatDate(lastTime)
    })
  })

  const keyword = searchKeyword.trim().toLowerCase()
  const filtered = keyword
    ? chatInfos.filter(info => info.title.toLowerCase().includes(keyword) || info.fullContent.toLowerCase().includes(keyword))
    : chatInfos
  filtered.sort(compareChatInfo)

  const validFolderIds = new Set(folders.map(folder => folder.id))
  const groupingByFolder = !keyword
  const folderGroups = groupingByFolder ? buildFolderGroups(filtered, folders, validFolderIds) : []
  const { groups, groupOrder } = buildDateGroups(filtered, validFolderIds, groupingByFolder, now)
  return { groups, groupOrder, total: filtered.length, folderGroups }
}

/**
 * 规整服务端或备份返回的文件夹列表，剔除非法项和重复 ID。
 */
export function normalizeChatFolders(raw) {
  const list = []
  const seen = new Set()
  if (!Array.isArray(raw)) return list
  raw.forEach(folder => {
    if (!folder || typeof folder !== 'object') return
    const id = typeof folder.id === 'string' ? folder.id : String(folder.id || '')
    const name = typeof folder.name === 'string' ? folder.name.trim() : ''
    if (!id || !name || seen.has(id)) return
    seen.add(id)
    list.push({ id, name, collapsed: !!folder.collapsed })
  })
  return list
}

/** 解析兼容斜杠与横杠格式的会话时间。 */
function parseChatDate(timeText) {
  if (!timeText) return null
  try {
    let date = new Date(timeText.replace(/\//g, '-'))
    if (!Number.isNaN(date.getTime())) return date
    date = new Date(timeText)
    if (!Number.isNaN(date.getTime())) return date
  } catch {
    // 非法时间按未知日期处理。
  }
  return null
}

/** 按置顶状态与最后对话时间排序会话。 */
function compareChatInfo(left, right) {
  if (left.pinned !== right.pinned) return left.pinned ? -1 : 1
  if (!left.lastTimeDate && !right.lastTimeDate) return 0
  if (!left.lastTimeDate) return -1
  if (!right.lastTimeDate) return 1
  return right.lastTimeDate - left.lastTimeDate
}

/** 构建文件夹分组，并保持文件夹定义顺序。 */
function buildFolderGroups(chats, folders, validFolderIds) {
  const byFolder = {}
  chats.forEach(chat => {
    if (!chat.folderId || !validFolderIds.has(chat.folderId)) return
    if (!byFolder[chat.folderId]) byFolder[chat.folderId] = []
    byFolder[chat.folderId].push(chat)
  })
  return folders.map(folder => ({ folder, chats: byFolder[folder.id] || [] }))
}

/** 构建置顶与日期分组。 */
function buildDateGroups(chats, validFolderIds, groupingByFolder, now) {
  const groups = {}
  const groupOrder = []
  chats.forEach(chat => {
    if (groupingByFolder && chat.folderId && validFolderIds.has(chat.folderId)) return
    const dateLabel = chat.pinned ? '置顶' : getDateLabel(chat.lastTimeDate, now)
    if (!groups[dateLabel]) {
      groups[dateLabel] = []
      groupOrder.push(dateLabel)
    }
    groups[dateLabel].push(chat)
  })
  return { groups, groupOrder }
}

/** 将会话日期映射为今天、本周、本月或年月标签。 */
function getDateLabel(date, now) {
  if (!date) return '今天'
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const chatDate = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  if (chatDate.getTime() === today.getTime()) return '今天'

  const dayOfWeekFromMonday = (today.getDay() + 6) % 7
  const weekStart = new Date(today)
  weekStart.setDate(today.getDate() - dayOfWeekFromMonday)
  if (chatDate.getTime() >= weekStart.getTime() && chatDate.getTime() < today.getTime()) return '本周'
  if (chatDate.getFullYear() === today.getFullYear() && chatDate.getMonth() === today.getMonth()) return '本月'

  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  return `${year}年${month}月`
}
