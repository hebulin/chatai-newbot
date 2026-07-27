import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loadChatHistory, saveChatHistory } from '@/api/chat'

export const useChatStore = defineStore('chat', () => {
  const chats = ref({})
  const currentChatId = ref(null)
  const deletedChatIds = ref([])
  const isChatHistoryLoaded = ref(false)
  const searchKeyword = ref('')

  let syncTimer = null

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
      const title = first ? first.content.substring(0, 20) : '新会话'
      chatInfos.push({
        id,
        title,
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

    // 按最后对话时间排序
    filtered.sort((a, b) => {
      if (!a.lastTimeDate && !b.lastTimeDate) return 0
      if (!a.lastTimeDate) return -1
      if (!b.lastTimeDate) return 1
      return b.lastTimeDate - a.lastTimeDate
    })

    // 按日期分组
    const groups = {}
    const groupOrder = []
    filtered.forEach(info => {
      const dateLabel = getDateLabel(info.lastTimeDate)
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
    const diffDays = Math.floor((today.getTime() - chatDate.getTime()) / 86400000)
    if (diffDays === 0) return '今天'
    if (diffDays <= 30) return '30天内'
    const year = date.getFullYear()
    const month = date.getMonth() + 1
    if (year === now.getFullYear()) return month + '月'
    return year + '年' + month + '月'
  }

  // 从服务端加载会话历史
  async function loadFromServer() {
    try {
      const data = await loadChatHistory()
      if (data && data.success) {
        chats.value = data.chats || {}
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
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = setTimeout(async () => {
      try {
        await saveChatHistory({
          lastChatId: currentChatId.value,
          chats: chats.value,
          deletedChatIds: deletedChatIds.value
        })
      } catch (e) {
        console.error('会话同步失败:', e)
      }
    }, 500)
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

  function countValidChats() {
    let count = 0
    Object.keys(chats.value).forEach(id => {
      const msgs = chats.value[id] || []
      if (msgs.some(m => m.role === 'user')) count++
    })
    return count
  }

  return {
    chats, currentChatId, deletedChatIds, isChatHistoryLoaded, searchKeyword,
    sortedChatList, currentMessages,
    loadFromServer, syncToServer, newChat, switchChat, deleteChat, deleteAllChats,
    addMessage, updateLastAssistantMessage, exportChats, countValidChats, findEmptyChatId
  }
})
