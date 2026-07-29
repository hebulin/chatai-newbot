import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loadChatHistory, saveChatHistory, loadSingleChatHistory } from '@/api/chat'

export const useChatStore = defineStore('chat', () => {
  const chats = ref({})
  const chatMeta = ref({})
  const currentChatId = ref(null)
  const deletedChatIds = ref([])
  const isChatHistoryLoaded = ref(false)
  const searchKeyword = ref('')

  let syncTimer = null
  // 全量同步挂起标记：发送消息→bot 输出期间不执行全量上传，结束后统一补一次，
  // 避免历史会话多的用户在发送时占用当前会话同步的网络开销
  let syncSuspended = false
  let pendingSyncWhileSuspended = false

  // 按日期分组的会话列表
  const sortedChatList = computed(() => {
    const chatInfos = []
    const ids = Object.keys(chats.value)
    ids.forEach(id => {
      const msgs = chats.value[id] || []
      let first = null
      let lastTime = null
      for (let i = 0; i < msgs.length; i++) {
        if (msgs[i].role === 'user' && !first) first = msgs[i]
        if (msgs[i].time) lastTime = msgs[i].time
      }
      const meta = chatMeta.value[id] || {}
      const autoTitle = first ? first.content.substring(0, 20) : '新会话'
      const title = (meta.title && meta.title.trim()) ? meta.title : autoTitle
      chatInfos.push({
        id,
        title,
        pinned: !!meta.pinned,
        fullContent: first ? first.content : '',
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

    // 按日期分组（置顶会话归入“置顶”分组，因排序后靠前故分组也在最前）
    const groups = {}
    const groupOrder = []
    filtered.forEach(info => {
      const dateLabel = info.pinned ? '置顶' : getDateLabel(info.lastTimeDate)
      if (!groups[dateLabel]) {
        groups[dateLabel] = []
        groupOrder.push(dateLabel)
      }
      groups[dateLabel].push(info)
    })

    return { groups, groupOrder, total: filtered.length }
  })

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

  // 从服务端加载会话历史
  async function loadFromServer() {
    try {
      const data = await loadChatHistory()
      if (data && data.success) {
        chats.value = data.chats || {}
        chatMeta.value = data.chatMeta || {}
        deletedChatIds.value = data.deletedChatIds || []
        const lastId = data.lastChatId
        if (lastId && chats.value[lastId]) {
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

  // 同步到服务端（500ms防抖）
  function syncToServer() {
    if (!isChatHistoryLoaded.value) return
    if (syncSuspended) {
      pendingSyncWhileSuspended = true
      return
    }
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = setTimeout(async () => {
      try {
        await saveChatHistory({
          lastChatId: currentChatId.value,
          chats: chats.value,
          chatMeta: chatMeta.value,
          deletedChatIds: deletedChatIds.value
        })
      } catch (e) {
        console.error('会话同步失败:', e)
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

  function newChat() {
    currentChatId.value = Date.now().toString()
    chats.value[currentChatId.value] = []
    syncToServer()
  }

  function switchChat(id) {
    currentChatId.value = id
    syncToServer()
  }

  function deleteChat(id) {
    delete chats.value[id]
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
    const ids = Object.keys(chats.value)
    ids.forEach(id => deletedChatIds.value.push(id))
    chats.value = {}
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

  function exportChats() {
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
  }

  // 全量 JSON 备份：导出会话内容与元信息，可用于跨账号/跨部署迁移后导回
  function exportChatsJson() {
    const data = {
      app: 'chatai-newbot',
      version: 1,
      exportedAt: new Date().toISOString(),
      chats: chats.value,
      chatMeta: chatMeta.value
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
      // 已有同 ID 且有内容的会话不覆盖，避免导入旧备份丢失新消息
      if (existing && existing.length > 0) { skipped++; return }
      chats.value[id] = msgs
      if (srcMeta[id] && typeof srcMeta[id] === 'object') {
        chatMeta.value[id] = { ...srcMeta[id] }
      }
      // 从删除列表移除，防止服务端将导入的会话当作已删除而丢弃
      const delIdx = deletedChatIds.value.indexOf(id)
      if (delIdx >= 0) deletedChatIds.value.splice(delIdx, 1)
      imported++
    })
    if (imported > 0) syncToServer()
    return { imported, skipped }
  }

  function countValidChats() {
    let count = 0
    Object.keys(chats.value).forEach(id => {
      const msgs = chats.value[id] || []
      if (msgs.some(m => m.role === 'user')) count++
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

  // 导出单个会话为 Markdown
  function exportChatMarkdown(id) {
    const msgs = chats.value[id] || []
    const meta = chatMeta.value[id] || {}
    let first = msgs.find(m => m.role === 'user')
    const title = (meta.title && meta.title.trim())
      ? meta.title
      : (first ? first.content.substring(0, 20) : '新会话')
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
    chats, chatMeta, currentChatId, deletedChatIds, isChatHistoryLoaded, searchKeyword,
    sortedChatList, currentMessages,
    loadFromServer, syncToServer, suspendSync, resumeSync, syncCurrentChatFromServer,
    newChat, switchChat, deleteChat, deleteAllChats,
    addMessage, truncateMessages, updateLastAssistantMessage, exportChats, exportChatsJson, importChatsJson, countValidChats, findEmptyChatId,
    togglePin, renameChat, setAutoTitleIfEmpty, exportChatMarkdown, setChatPromptPreset
  }
})
