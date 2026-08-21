import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loadChatHistory, loadChatSummaries, loadChatVersion, saveChatHistory, loadSingleChatHistory } from '@/api/chat'

export const useChatStore = defineStore('chat', () => {
  const chats = ref({})
  // 未加载会话的摘要（id -> { title, preview, lastTime, count }）：
  // 懒加载模式下首屏只拉摘要，会话正文切换时才按需加载进 chats
  const chatSummaries = ref({})
  const chatMeta = ref({})
  const currentChatId = ref(null)
  const deletedChatIds = ref([])
  const isChatHistoryLoaded = ref(false)
  const searchKeyword = ref('')
  // 会话文件夹定义列表：[{ id, name, collapsed }]；会话归属存于各会话 meta.folderId
  const folders = ref([])

  let syncTimer = null
  // 全量同步挂起标记：发送消息→bot 输出期间不执行全量上传，结束后统一补一次，
  // 避免历史会话多的用户在发送时占用当前会话同步的网络开销
  let syncSuspended = false
  let pendingSyncWhileSuspended = false
  // 上传请求进行中标记：自动同步刷新需避开上传窗口，防止服务端旧快照覆盖本地新变更
  let syncInFlight = false
  // 已知的服务端版本号（updated_at_ts 最大值）：多端自动同步的变更检测基准
  let remoteVersion = 0
  // 自动刷新进行中标记，防重入
  let refreshing = false

  // 按日期分组的会话列表（已加载会话按消息实时推导，未加载会话用服务端摘要）
  const sortedChatList = computed(() => {
    const chatInfos = []
    const ids = new Set([...Object.keys(chats.value), ...Object.keys(chatSummaries.value)])
    ids.forEach(id => {
      const meta = chatMeta.value[id] || {}
      let autoTitle, fullContent, lastTime
      if (chats.value[id] !== undefined) {
        const msgs = chats.value[id] || []
        let first = null
        lastTime = null
        for (let i = 0; i < msgs.length; i++) {
          if (msgs[i].role === 'user' && !first) first = msgs[i]
          if (msgs[i].time) lastTime = msgs[i].time
        }
        // 空会话（尚未发送过用户消息）不在侧边栏展示，发送首条消息后才出现
        if (!first) return
        autoTitle = first.content.substring(0, 20)
        fullContent = first.content
      } else {
        const s = chatSummaries.value[id] || {}
        // 未加载会话：服务端摘要显示无消息的空会话同样不展示
        if ((s.count || 0) === 0) return
        autoTitle = s.title || '新会话'
        fullContent = s.preview || ''
        lastTime = s.lastTime || null
      }
      const title = (meta.title && meta.title.trim()) ? meta.title : autoTitle
      chatInfos.push({
        id,
        title,
        pinned: !!meta.pinned,
        folderId: meta.folderId || null,
        fullContent,
        lastTime,
        lastTimeDate: parseDateFromStr(lastTime)
      })
    })

    // 模糊搜索过滤
    const keyword = searchKeyword.value.trim().toLowerCase()
    let filtered = chatInfos
    if (keyword) {
      filtered = chatInfos.filter(info =>
        info.title.toLowerCase().includes(keyword) ||
        info.fullContent.toLowerCase().includes(keyword)
      )
    }

    // 置顶会话优先，其次按最后对话时间排序
    filtered.sort((a, b) => {
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
      if (!a.lastTimeDate && !b.lastTimeDate) return 0
      if (!a.lastTimeDate) return -1
      if (!b.lastTimeDate) return 1
      return b.lastTimeDate - a.lastTimeDate
    })

    const validFolderIds = new Set(folders.value.map(f => f.id))

    // 搜索时不做文件夹分组：命中的会话（含文件夹内）平铺到日期分组，便于快速定位
    const groupingByFolder = !keyword
    let folderGroups = []
    if (groupingByFolder) {
      const byFolder = {}
      filtered.forEach(info => {
        if (info.folderId && validFolderIds.has(info.folderId)) {
          if (!byFolder[info.folderId]) byFolder[info.folderId] = []
          byFolder[info.folderId].push(info)
        }
      })
      folderGroups = folders.value
        .map(f => ({ folder: f, chats: byFolder[f.id] || [] }))
    }

    // 按日期分组（置顶会话归入“置顶”分组，因排序后靠前故分组也在最前）；
    // 分组阶段排除已归入文件夹的会话（搜索态除外）
    const groups = {}
    const groupOrder = []
    filtered.forEach(info => {
      if (groupingByFolder && info.folderId && validFolderIds.has(info.folderId)) return
      const dateLabel = info.pinned ? '置顶' : getDateLabel(info.lastTimeDate)
      if (!groups[dateLabel]) {
        groups[dateLabel] = []
        groupOrder.push(dateLabel)
      }
      groups[dateLabel].push(info)
    })

    return { groups, groupOrder, total: filtered.length, folderGroups }
  })

  // 规整服务端/备份返回的文件夹列表：仅保留含合法 id 与 name 的项，去除重复 id
  function normalizeFolders(raw) {
    const list = []
    const seen = new Set()
    if (!Array.isArray(raw)) return list
    raw.forEach(f => {
      if (!f || typeof f !== 'object') return
      const id = typeof f.id === 'string' ? f.id : String(f.id || '')
      const name = typeof f.name === 'string' ? f.name.trim() : ''
      if (!id || !name || seen.has(id)) return
      seen.add(id)
      list.push({ id, name, collapsed: !!f.collapsed })
    })
    return list
  }

  const currentMessages = computed(() => {
    return chats.value[currentChatId.value] || []
  })

  function parseDateFromStr(timeStr) {
    if (!timeStr) return null
    try {
      let date = new Date(timeStr.replace(/\//g, '-'))
      if (!isNaN(date.getTime())) return date
      date = new Date(timeStr)
      if (!isNaN(date.getTime())) return date
    } catch (e) { /* ignore */ }
    return null
  }

  function getDateLabel(date) {
    if (!date) return '今天'
    const now = new Date()
    const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
    const chatDate = new Date(date.getFullYear(), date.getMonth(), date.getDate())

    // 今天
    if (chatDate.getTime() === today.getTime()) return '今天'

    // 本周（以周一为一周起点，排除今天）
    const dowMon = (today.getDay() + 6) % 7 // 周一=0 … 周日=6
    const weekStart = new Date(today)
    weekStart.setDate(today.getDate() - dowMon)
    if (chatDate.getTime() >= weekStart.getTime() && chatDate.getTime() < today.getTime()) return '本周'

    // 本月（同年同月，排除已归入本周的部分）
    if (chatDate.getFullYear() === today.getFullYear() && chatDate.getMonth() === today.getMonth()) return '本月'

    // 超过本月：按月份区分，格式 YYYY年MM月（月份补零，如 2025年06月）
    const year = date.getFullYear()
    const month = String(date.getMonth() + 1).padStart(2, '0')
    return `${year}年${month}月`
  }

  // 从服务端加载会话列表（只拉摘要，不含消息内容），再按需加载上次打开的会话
  async function loadFromServer() {
    try {
      const data = await loadChatSummaries()
      if (data && data.success) {
        chats.value = {}
        chatMeta.value = data.chatMeta || {}
        deletedChatIds.value = data.deletedChatIds || []
        folders.value = normalizeFolders(data.folders)
        remoteVersion = data.version || 0
        const map = {}
        ;(data.summaries || []).forEach(s => { if (s && s.id) map[s.id] = s })
        chatSummaries.value = map
        const lastId = data.lastChatId
        if (lastId && map[lastId]) {
          await ensureChatLoaded(lastId)
          currentChatId.value = lastId
        } else {
          newChat()
        }
      } else {
        chats.value = {}
        currentChatId.value = null
        newChat()
      }
    } catch (e) {
      console.error('加载会话历史失败:', e)
      chats.value = {}
      currentChatId.value = null
      newChat()
    }
    isChatHistoryLoaded.value = true
  }

  // 按需加载会话正文：未加载时从服务端拉取单会话消息；加载失败时抛出异常，
  // 绝不能置空数组占位，否则后续同步会把服务端该会话内容覆盖为空
  async function ensureChatLoaded(id, config) {
    if (!id || chats.value[id] !== undefined) return
    // 摘要中不存在的 ID 视为本地新会话，直接初始化
    if (!chatSummaries.value[id]) {
      chats.value[id] = []
      return
    }
    const res = await loadSingleChatHistory(id, config)
    if (!res || !res.success) {
      throw new Error((res && res.message) || '加载会话内容失败')
    }
    chats.value[id] = Array.isArray(res.messages) ? res.messages : []
    if (res.meta && typeof res.meta === 'object') {
      chatMeta.value[id] = { ...res.meta }
    }
  }

  // 拉取全量会话内容（导出备份用）：未加载的会话用服务端数据补齐，已加载的以本地为准
  async function ensureAllChatsLoaded() {
    const missing = Object.keys(chatSummaries.value).filter(id => chats.value[id] === undefined)
    if (missing.length === 0) return
    const data = await loadChatHistory()
    if (!data || !data.success || !data.chats) {
      throw new Error('拉取全量会话失败')
    }
    missing.forEach(id => {
      if (data.chats[id] !== undefined) chats.value[id] = data.chats[id]
    })
  }

  // 同步到服务端（500ms防抖）
  function syncToServer() {
    if (!isChatHistoryLoaded.value) return
    if (syncSuspended) {
      pendingSyncWhileSuspended = true
      return
    }
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = setTimeout(async () => {
      syncTimer = null
      syncInFlight = true
      try {
        const res = await saveChatHistory({
          lastChatId: currentChatId.value,
          chats: chats.value,
          chatMeta: chatMeta.value,
          deletedChatIds: deletedChatIds.value,
          folders: folders.value
        })
        // 记住保存后的版本基准，自己的写入不触发下一轮自动同步重拉
        if (res && res.success && res.version) {
          remoteVersion = res.version
        }
      } catch (e) {
        console.error('会话同步失败:', e)
      } finally {
        syncInFlight = false
      }
    }, 500)
  }

  // 挂起全量同步（发送消息前调用）
  function suspendSync() {
    syncSuspended = true
    if (syncTimer) {
      clearTimeout(syncTimer)
      syncTimer = null
      pendingSyncWhileSuspended = true
    }
  }

  // 恢复全量同步（bot 输出结束后调用）；挂起期间有变更则立即补同步
  function resumeSync() {
    syncSuspended = false
    if (pendingSyncWhileSuspended) {
      pendingSyncWhileSuspended = false
      syncToServer()
    }
  }

  // 发送前同步当前会话：拉取服务端该会话最新记录（其他端可能已新增消息），
  // 若服务端比本地基线（不含末尾 pendingCount 条未上传的新消息）更全，
  // 则用服务端记录替换基线并保留末尾新消息，返回是否发生了合并
  async function syncCurrentChatFromServer(chatId, pendingCount = 0) {
    const res = await loadSingleChatHistory(chatId)
    if (!res || !res.success || !Array.isArray(res.messages)) return false
    const serverMsgs = res.messages
    const local = chats.value[chatId] || []
    const baseLen = Math.max(0, local.length - pendingCount)
    if (serverMsgs.length <= baseLen) return false
    const pendingTail = local.slice(baseLen)
    chats.value[chatId] = [...serverMsgs, ...pendingTail]
    // 同步会话元信息（其他端可能已重命名/置顶）
    if (res.meta && typeof res.meta === 'object') {
      chatMeta.value[chatId] = { ...res.meta }
    }
    return true
  }

  // 侧边栏自动同步（多端）：先查版本号，有变更才重拉摘要并合并到本地。
  // 本地存在待上传/上传中的变更时跳过本轮（上传完成后服务端即最新，
  // 下轮再合并），确保应用服务端状态时服务端已包含本地全部变更，合并可以服务端为准。
  // 返回当前会话正文是否被合并更新（调用方据此决定是否贴底滚动）
  async function refreshFromServer() {
    if (!isChatHistoryLoaded.value || syncSuspended || syncTimer || syncInFlight || refreshing) return false
    refreshing = true
    try {
      const v = await loadChatVersion()
      if (!v || !v.success || !v.version || v.version === remoteVersion) return false
      const data = await loadChatSummaries()
      if (!data || !data.success) return false
      // 拉取期间本地可能产生了新变更，本轮放弃，避免服务端旧快照覆盖本地
      if (syncSuspended || syncTimer || syncInFlight) return false
      applyServerState(data)
      remoteVersion = data.version || v.version
      // 当前会话在其他端有新增消息：复用发送前同步逻辑合并正文
      const curId = currentChatId.value
      const curSummary = chatSummaries.value[curId]
      if (curSummary && (curSummary.count || 0) > (chats.value[curId] || []).length) {
        return await syncCurrentChatFromServer(curId, 0)
      }
      return false
    } catch (e) {
      console.error('会话列表自动同步失败:', e)
      return false
    } finally {
      refreshing = false
    }
  }

  // 将服务端摘要状态合并到本地（仅在本地无待上传变更时调用，服务端为准）
  function applyServerState(data) {
    const map = {}
    ;(data.summaries || []).forEach(s => { if (s && s.id) map[s.id] = s })
    // 本地已删除的会话不复活
    deletedChatIds.value.forEach(id => { delete map[id] })

    Object.keys(chats.value).forEach(id => {
      if (id === currentChatId.value) return
      if (!map[id]) {
        // 其他端已删除：本地同步移除（当前会话除外，避免正在查看时被抽走）
        delete chats.value[id]
        delete chatMeta.value[id]
        return
      }
      // 其他端更新过的已加载会话踢回未加载态，切换时按需重拉最新正文，
      // 防止本地陈旧副本在下次全量同步时覆盖服务端新内容
      const local = chats.value[id] || []
      let localLast = null
      for (let i = 0; i < local.length; i++) {
        if (local[i].time) localLast = local[i].time
      }
      if ((map[id].count || 0) !== local.length || (map[id].lastTime || null) !== localLast) {
        delete chats.value[id]
      }
    })

    // 摘要与元信息以服务端为准；服务端未知的本地会话（如刚新建的当前会话）保留本地元信息
    chatSummaries.value = map
    const serverMeta = data.chatMeta || {}
    const mergedMeta = {}
    Object.keys(serverMeta).forEach(id => {
      if (map[id]) mergedMeta[id] = serverMeta[id]
    })
    Object.keys(chatMeta.value).forEach(id => {
      if (mergedMeta[id] === undefined && chats.value[id] !== undefined) {
        mergedMeta[id] = chatMeta.value[id]
      }
    })
    chatMeta.value = mergedMeta
    // 文件夹定义以服务端为准（本地待上传变更已被守卫排除）
    folders.value = normalizeFolders(data.folders)
    // 已删除列表以服务端累积合并后的为准（本地待上传变更已被守卫排除）
    deletedChatIds.value = data.deletedChatIds || []
  }

  function newChat() {
    // 已有空会话时直接复用，避免反复点击新建产生多个空会话
    const emptyId = findEmptyChatId()
    if (emptyId) {
      if (chats.value[emptyId] === undefined) chats.value[emptyId] = []
      currentChatId.value = emptyId
      syncToServer()
      return
    }
    currentChatId.value = Date.now().toString()
    chats.value[currentChatId.value] = []
    syncToServer()
  }

  function switchChat(id) {
    currentChatId.value = id
    syncToServer()
  }

  // 切换会话（懒加载）：先确保目标会话正文已加载再切换，加载失败时保持当前会话不变
  async function switchChatLazy(id, config) {
    await ensureChatLoaded(id, config)
    switchChat(id)
  }

  function deleteChat(id) {
    delete chats.value[id]
    delete chatSummaries.value[id]
    delete chatMeta.value[id]
    deletedChatIds.value.push(id)
    syncToServer()
    if (id === currentChatId.value) {
      const emptyId = findEmptyChatId()
      if (emptyId) {
        currentChatId.value = emptyId
      } else {
        newChat()
      }
    }
  }

  function deleteAllChats() {
    const ids = new Set([...Object.keys(chats.value), ...Object.keys(chatSummaries.value)])
    ids.forEach(id => deletedChatIds.value.push(id))
    chats.value = {}
    chatSummaries.value = {}
    chatMeta.value = {}
    currentChatId.value = null
    newChat()
  }

  function findEmptyChatId() {
    const ids = Object.keys(chats.value)
    for (let i = 0; i < ids.length; i++) {
      const msgs = chats.value[ids[i]]
      if (!msgs || msgs.length === 0) return ids[i]
      const hasUserMsg = msgs.some(m => m.role === 'user')
      if (!hasUserMsg) return ids[i]
    }
    // 未加载但摘要显示无消息的会话也视为空会话
    const sids = Object.keys(chatSummaries.value)
    for (let i = 0; i < sids.length; i++) {
      if (chats.value[sids[i]] !== undefined) continue
      if ((chatSummaries.value[sids[i]].count || 0) === 0) return sids[i]
    }
    return null
  }

  function addMessage(chatId, msg) {
    if (!chats.value[chatId]) chats.value[chatId] = []
    chats.value[chatId].push(msg)
    syncToServer()
  }

  // 截断会话消息：删除 fromIdx（含）之后的所有消息（用于重新生成/编辑重发）
  function truncateMessages(chatId, fromIdx) {
    const msgs = chats.value[chatId]
    if (!msgs) return
    msgs.splice(fromIdx)
    syncToServer()
  }

  function updateLastAssistantMessage(chatId, msg) {
    const msgs = chats.value[chatId]
    if (!msgs) return
    const lastIdx = msgs.length - 1
    if (lastIdx >= 0 && msgs[lastIdx].role === 'assistant') {
      msgs[lastIdx] = msg
    } else {
      msgs.push(msg)
    }
    syncToServer()
  }

  // 会话标题：自定义标题优先，其次首条用户消息前 20 字（未加载会话回退服务端摘要标题）
  function chatTitle(id) {
    const meta = chatMeta.value[id] || {}
    if (meta.title && meta.title.trim()) return meta.title.trim()
    const first = (chats.value[id] || []).find(m => m.role === 'user')
    if (first) return String(first.content || '').substring(0, 20)
    const s = chatSummaries.value[id]
    return (s && s.title) ? s.title : '新会话'
  }

  // 导出全部会话（先补齐未加载的会话内容），format: txt=纯文本 md=Markdown json=完整 JSON 备份
  async function exportChats(format = 'txt') {
    await ensureAllChatsLoaded()
    if (format === 'json') {
      exportChatsJson()
      return
    }
    if (format === 'md') {
      exportChatsMarkdown()
      return
    }
    let text = ''
    Object.entries(chats.value).forEach((entry, idx) => {
      if (idx > 0) text += '\n====================\n\n'
      entry[1].forEach(m => {
        text += '[' + (m.time || '') + '] ' + m.role + ': ' + m.content + '\n'
      })
    })
    const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'chat-export-' + new Date().toISOString().slice(0, 10) + '.txt'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  // 全量 Markdown 导出：每个会话一个一级标题，会话之间以分隔线分隔（格式与单会话导出一致）
  function exportChatsMarkdown() {
    const sections = []
    Object.keys(chats.value).forEach(id => {
      const msgs = chats.value[id] || []
      if (!msgs.some(m => m.role === 'user')) return
      let md = '# ' + chatTitle(id) + '\n\n'
      msgs.forEach(m => {
        const roleLabel = m.role === 'user' ? '👤 用户' : '🤖 助手'
        md += '## ' + roleLabel + (m.time ? '  `' + m.time + '`' : '') + '\n\n'
        md += (m.content || '') + '\n\n'
      })
      sections.push(md.trimEnd())
    })
    const blob = new Blob([sections.join('\n\n---\n\n') + '\n'], { type: 'text/markdown;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'chat-export-' + new Date().toISOString().slice(0, 10) + '.md'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  // 全量 JSON 备份：导出会话内容与元信息，可用于跨账号/跨部署迁移后导回
  function exportChatsJson() {
    const data = {
      app: 'chatai-newbot',
      version: 1,
      exportedAt: new Date().toISOString(),
      chats: chats.value,
      chatMeta: chatMeta.value,
      folders: folders.value
    }
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = 'chat-backup-' + new Date().toISOString().slice(0, 10) + '.json'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  // 导入 JSON 备份：按会话 ID 合并，已存在的会话跳过不覆盖；返回 { imported, skipped }
  function importChatsJson(data) {
    if (!data || typeof data !== 'object' || !data.chats || typeof data.chats !== 'object') {
      throw new Error('备份文件格式不正确（缺少 chats 字段）')
    }
    let imported = 0
    let skipped = 0
    const srcMeta = (data.chatMeta && typeof data.chatMeta === 'object') ? data.chatMeta : {}
    Object.keys(data.chats).forEach(id => {
      const msgs = data.chats[id]
      if (!Array.isArray(msgs)) { skipped++; return }
      const existing = chats.value[id]
      const summary = chatSummaries.value[id]
      // 已有同 ID 且有内容的会话（含未加载但摘要显示有消息的）不覆盖，避免导入旧备份丢失新消息
      if (existing && existing.length > 0) { skipped++; return }
      if (!existing && summary && (summary.count || 0) > 0) { skipped++; return }
      chats.value[id] = msgs
      if (srcMeta[id] && typeof srcMeta[id] === 'object') {
        chatMeta.value[id] = { ...srcMeta[id] }
      }
      // 从删除列表移除，防止服务端将导入的会话当作已删除而丢弃
      const delIdx = deletedChatIds.value.indexOf(id)
      if (delIdx >= 0) deletedChatIds.value.splice(delIdx, 1)
      imported++
    })
    // 合并备份中的文件夹定义：同名/同 id 冲突时以本地现有文件夹为准，仅补充缺失项
    let foldersChanged = false
    const backupFolders = normalizeFolders(data.folders)
    if (backupFolders.length > 0) {
      const localIds = new Set(folders.value.map(f => f.id))
      const localNames = new Set(folders.value.map(f => f.name))
      backupFolders.forEach(f => {
        if (localIds.has(f.id) || localNames.has(f.name)) return
        folders.value.push({ ...f, collapsed: false })
        foldersChanged = true
      })
    }
    if (imported > 0 || foldersChanged) syncToServer()
    return { imported, skipped }
  }

  function countValidChats() {
    let count = 0
    const ids = new Set([...Object.keys(chats.value), ...Object.keys(chatSummaries.value)])
    ids.forEach(id => {
      if (chats.value[id] !== undefined) {
        if ((chats.value[id] || []).some(m => m.role === 'user')) count++
      } else if ((chatSummaries.value[id].preview || '') !== '') {
        // 未加载会话：摘要预览非空即含用户消息
        count++
      }
    })
    return count
  }

  // 置顶/取消置顶会话
  function togglePin(id) {
    const meta = chatMeta.value[id] || {}
    meta.pinned = !meta.pinned
    chatMeta.value[id] = { ...meta }
    syncToServer()
  }

  // 重命名会话（title 为空/纯空白则清除自定义标题，回退首条用户消息）
  function renameChat(id, title) {
    const meta = chatMeta.value[id] || {}
    const t = (title || '').trim()
    if (t) {
      meta.title = t
    } else {
      delete meta.title
    }
    chatMeta.value[id] = { ...meta }
    syncToServer()
  }

  // 绑定/解绑会话的角色提示词预设（presetId 为空则恢复默认，跟随全局提示词设置）
  function setChatPromptPreset(id, presetId) {
    const meta = chatMeta.value[id] || {}
    if (presetId) {
      meta.promptPresetId = presetId
    } else {
      delete meta.promptPresetId
    }
    chatMeta.value[id] = { ...meta }
    syncToServer()
  }

  // ========== 会话文件夹 ==========

  // 新建文件夹并返回其 id；重名时复用已有文件夹
  function createFolder(name) {
    const n = (name || '').trim()
    if (!n) return null
    const existing = folders.value.find(f => f.name === n)
    if (existing) return existing.id
    const id = 'folder_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7)
    folders.value.push({ id, name: n, collapsed: false })
    syncToServer()
    return id
  }

  // 重命名文件夹（空名称忽略）
  function renameFolder(id, name) {
    const n = (name || '').trim()
    if (!n) return
    const f = folders.value.find(x => x.id === id)
    if (!f || f.name === n) return
    // 避免与现有文件夹重名
    if (folders.value.some(x => x.id !== id && x.name === n)) return
    f.name = n
    syncToServer()
  }

  // 删除文件夹：仅移除分组容器，其内会话恢复为未分组状态
  function deleteFolder(id) {
    const idx = folders.value.findIndex(f => f.id === id)
    if (idx < 0) return
    folders.value.splice(idx, 1)
    Object.keys(chatMeta.value).forEach(chatId => {
      const meta = chatMeta.value[chatId]
      if (meta && meta.folderId === id) {
        delete meta.folderId
        chatMeta.value[chatId] = { ...meta }
      }
    })
    syncToServer()
  }

  // 展开/收起文件夹
  function toggleFolderCollapsed(id) {
    const f = folders.value.find(x => x.id === id)
    if (!f) return
    f.collapsed = !f.collapsed
    syncToServer()
  }

  // 将会话移入文件夹（folderId 为空则移出所有文件夹）
  function moveChatToFolder(chatId, folderId) {
    const meta = chatMeta.value[chatId] || {}
    if (folderId && folders.value.some(f => f.id === folderId)) {
      meta.folderId = folderId
    } else {
      delete meta.folderId
    }
    chatMeta.value[chatId] = { ...meta }
    syncToServer()
  }

  // 设置会话标题（仅当未手动命名时生效，用于 AI 自动命名）
  function setAutoTitleIfEmpty(id, title) {
    const t = (title || '').trim()
    if (!t) return
    const meta = chatMeta.value[id] || {}
    if (meta.title && meta.title.trim()) return
    meta.title = t
    chatMeta.value[id] = { ...meta }
    syncToServer()
  }

  // 导出单个会话为 Markdown（未加载时先拉取正文）
  async function exportChatMarkdown(id) {
    await ensureChatLoaded(id)
    const msgs = chats.value[id] || []
    const title = chatTitle(id)
    let md = '# ' + title + '\n\n'
    msgs.forEach(m => {
      const roleLabel = m.role === 'user' ? '👤 用户' : '🤖 助手'
      md += '## ' + roleLabel + (m.time ? '  `' + m.time + '`' : '') + '\n\n'
      md += (m.content || '') + '\n\n'
    })
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    const safeTitle = title.replace(/[\\/:*?"<>|]/g, '_').substring(0, 40)
    a.download = safeTitle + '.md'
    a.click()
    URL.revokeObjectURL(a.href)
  }

  return {
    chats, chatSummaries, chatMeta, currentChatId, deletedChatIds, isChatHistoryLoaded, searchKeyword,
    folders,
    sortedChatList, currentMessages,
    loadFromServer, syncToServer, suspendSync, resumeSync, syncCurrentChatFromServer, refreshFromServer,
    ensureChatLoaded, ensureAllChatsLoaded,
    newChat, switchChat, switchChatLazy, deleteChat, deleteAllChats,
    addMessage, truncateMessages, updateLastAssistantMessage, exportChats, exportChatsJson, importChatsJson, countValidChats, findEmptyChatId,
    togglePin, renameChat, setAutoTitleIfEmpty, exportChatMarkdown, setChatPromptPreset,
    createFolder, renameFolder, deleteFolder, toggleFolderCollapsed, moveChatToFolder
  }
})
