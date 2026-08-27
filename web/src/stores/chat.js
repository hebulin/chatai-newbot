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
  // 每会话的服务端版本号（乐观锁基准）：来自摘要/单会话加载/保存响应
  const chatVersions = ref({})
  // 文件夹定义的服务端版本号（文件夹并发修改的乐观锁基准）
  const foldersVersion = ref(0)
  // 待处理的同步冲突列表：[{ chatId, reason, serverVersion, title, localMessages, localMeta, serverMessages, serverMeta }]
  // reason: version=双端并发修改, deleted=其他端已删除, folders=文件夹定义冲突
  const syncConflicts = ref([])

  // 脏数据跟踪：仅同步实际变化的会话/元信息/文件夹，避免上传所有已加载会话的陈旧副本。
  // 值为"标脏后将要成为的 localRevision"（localRevision+1）：上传成功仅清除标脏 revision
  // 不超过本次快照 revision 的会话，防止上传期间的并发新修改被误清导致漏同步
  const dirtyChatIds = new Map()
  const dirtyMetaIds = new Map()
  let foldersDirty = false
  // 显式恢复的会话ID（备份导入/冲突解决选择保留本地）：下次同步时声明 restore，绕过删除保护
  const pendingRestoreIds = new Set()

  let syncTimer = null
  // 全量同步挂起标记：发送消息→bot 输出期间不执行全量上传，结束后统一补一次，
  // 避免历史会话多的用户在发送时占用当前会话同步的网络开销
  let syncSuspended = false
  let pendingSyncWhileSuspended = false
  // 上传请求进行中标记：自动同步刷新需避开上传窗口，防止服务端旧快照覆盖本地新变更
  let syncInFlight = false
  // 本地变更版本与串行上传状态：任一时刻最多一个保存请求，避免响应乱序覆盖最后修改
  let localRevision = 0
  let savedRevision = 0
  let flushPending = false
  let syncRetryDelay = 0
  // 已知的服务端版本号（updated_at_ts 最大值）：多端自动同步的变更检测基准
  let remoteVersion = 0
  // 自动刷新进行中标记，防重入
  let refreshing = false

  // 当前账号的未同步恢复快照键，避免不同用户之间读取彼此草稿
  function recoveryKey() {
    let username = 'anonymous'
    try { username = localStorage.getItem('username') || 'anonymous' } catch (e) { /* ignore */ }
    return `chatai-chat-recovery:${username}`
  }

  // 生成稳定的消息标识（幂等去重键）：重试上传时服务端/本地合并据此识别同一条消息
  function genMsgId() {
    return 'm' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)
  }

  // 标记会话内容已变更（消息增删改），下次同步上传该会话正文与元信息
  function markChatDirty(id) {
    if (!id) return
    dirtyChatIds.set(id, localRevision + 1)
    dirtyMetaIds.delete(id)
  }

  // 标记会话仅元信息变更（置顶/重命名/文件夹归属等），下次同步仅上传元信息
  function markMetaDirty(id) {
    if (!id || dirtyChatIds.has(id)) return
    dirtyMetaIds.set(id, localRevision + 1)
  }

  // 生成当前增量保存载荷：仅包含脏会话/脏元信息，携带每会话基准版本供服务端乐观锁校验
  function buildSyncPayload() {
    const chatsPayload = {}
    const metaPayload = {}
    dirtyChatIds.forEach((rev, id) => {
      if (chats.value[id] !== undefined) chatsPayload[id] = chats.value[id]
      if (chatMeta.value[id]) metaPayload[id] = chatMeta.value[id]
    })
    dirtyMetaIds.forEach((rev, id) => {
      if (!dirtyChatIds.has(id) && chatMeta.value[id]) metaPayload[id] = chatMeta.value[id]
    })
    const baseVersions = {}
    Object.keys(chatsPayload).forEach(id => { baseVersions[id] = chatVersions.value[id] || 0 })
    Object.keys(metaPayload).forEach(id => {
      if (baseVersions[id] === undefined) baseVersions[id] = chatVersions.value[id] || 0
    })
    return {
      lastChatId: currentChatId.value,
      chats: chatsPayload,
      chatMeta: metaPayload,
      deletedChatIds: [...deletedChatIds.value],
      folders: foldersDirty ? folders.value : undefined,
      baseVersions,
      baseFoldersVersion: foldersVersion.value,
      restoreChatIds: [...pendingRestoreIds],
      baseVersion: remoteVersion
    }
  }

  // 写入刷新/断网恢复快照；保存成功且无后续变更时才清除
  function persistRecoveryDraft(payload) {
    try {
      localStorage.setItem(recoveryKey(), JSON.stringify({ revision: localRevision, savedAt: Date.now(), payload }))
    } catch (e) { /* 存储空间不足时仍继续服务端同步 */ }
  }

  // 将尚未成功上传的本地恢复快照合并进服务端摘要状态
  function restoreRecoveryDraft() {
    try {
      const raw = localStorage.getItem(recoveryKey())
      if (!raw) return
      const draft = JSON.parse(raw)
      if (!draft?.payload || Date.now() - Number(draft.savedAt || 0) > 7 * 86400000) {
        localStorage.removeItem(recoveryKey())
        return
      }
      const payload = draft.payload
      Object.entries(payload.chats || {}).forEach(([id, messages]) => {
        const serverCount = Number(chatSummaries.value[id]?.count || 0)
        if (Array.isArray(messages) && (messages.length >= serverCount || !chatSummaries.value[id])) {
          chats.value[id] = messages
          // 恢复的草稿属于未上传变更，标记为脏数据等待重新同步
          dirtyChatIds.set(id, localRevision + 1)
        }
      })
      chatMeta.value = { ...chatMeta.value, ...(payload.chatMeta || {}) }
      Object.keys(payload.chatMeta || {}).forEach(id => {
        if (!dirtyChatIds.has(id)) dirtyMetaIds.set(id, localRevision + 1)
      })
      deletedChatIds.value = [...new Set([...deletedChatIds.value, ...(payload.deletedChatIds || [])])]
      if (Array.isArray(payload.folders)) {
        folders.value = normalizeFolders(payload.folders)
        foldersDirty = true
      }
      if (payload.lastChatId) currentChatId.value = payload.lastChatId
      localRevision = Number(draft.revision || 1)
      flushPending = true
    } catch (e) { /* 损坏草稿忽略，服务端数据仍可使用 */ }
  }

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
        foldersVersion.value = data.foldersVersion || 0
        remoteVersion = data.version || 0
        const map = {}
        const versions = {}
        ;(data.summaries || []).forEach(s => {
          if (s && s.id) {
            map[s.id] = s
            versions[s.id] = s.version || 0
          }
        })
        chatSummaries.value = map
        chatVersions.value = versions
        restoreRecoveryDraft()
        const lastId = data.lastChatId
        const recoveredId = currentChatId.value
        if (recoveredId && chats.value[recoveredId] !== undefined) {
          currentChatId.value = recoveredId
        } else if (lastId && map[lastId]) {
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
    if (flushPending) syncToServer()
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
    if (res.version) chatVersions.value[id] = res.version
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
    localRevision++
    persistRecoveryDraft(buildSyncPayload())
    if (syncSuspended) {
      pendingSyncWhileSuspended = true
      return
    }
    if (syncTimer) clearTimeout(syncTimer)
    syncTimer = setTimeout(flushSyncQueue, 500)
  }

  // 串行执行保存：上传期间发生的新修改会在当前请求结束后立即再保存一次
  async function flushSyncQueue() {
    syncTimer = null
    if (syncInFlight) {
      flushPending = true
      return
    }
    const revision = localRevision
    const payload = buildSyncPayload()
    syncInFlight = true
    flushPending = false
    try {
      const res = await saveChatHistory(payload)
      if (res?.success) {
        syncRetryDelay = 0
        savedRevision = revision
        if (res.version) remoteVersion = res.version
        // 应用成功：更新每会话服务端版本号并清除对应脏标记（仅清本次上传涉及的会话，
        // 上传期间新产生的变更已在 localRevision 中体现，由末尾的再保存逻辑兜底）
        if (res.versions && typeof res.versions === 'object') {
          Object.entries(res.versions).forEach(([id, v]) => { chatVersions.value[id] = v })
          Object.keys(payload.chats || {}).forEach(id => {
            // 仅清除本次快照之后未再变更的会话脏标记（防上传期间新修改被误清）
            if (res.versions[id] !== undefined && (dirtyChatIds.get(id) || 0) <= revision) dirtyChatIds.delete(id)
          })
          Object.keys(payload.chatMeta || {}).forEach(id => {
            if (res.versions[id] !== undefined && (dirtyMetaIds.get(id) || 0) <= revision) dirtyMetaIds.delete(id)
          })
        } else {
          // 旧服务端无逐会话版本返回：整体清除脏标记（兼容路径）
          Object.keys(payload.chats || {}).forEach(id => {
            if ((dirtyChatIds.get(id) || 0) <= revision) dirtyChatIds.delete(id)
          })
          Object.keys(payload.chatMeta || {}).forEach(id => {
            if ((dirtyMetaIds.get(id) || 0) <= revision) dirtyMetaIds.delete(id)
          })
        }
        if (typeof res.foldersVersion === 'number') {
          foldersVersion.value = res.foldersVersion
          if (payload.folders !== undefined) foldersDirty = false
        } else if (payload.folders !== undefined) {
          foldersDirty = false
        }
        ;(payload.restoreChatIds || []).forEach(id => pendingRestoreIds.delete(id))
        if (savedRevision === localRevision) {
          try { localStorage.removeItem(recoveryKey()) } catch (e) { /* ignore */ }
        }
        // 版本冲突处理：服务端未覆盖的数据在此合并，产生用户可见的冲突入口
        if (Array.isArray(res.conflicts) && res.conflicts.length > 0) {
          await handleSyncConflicts(res.conflicts, payload)
        }
      } else {
        flushPending = true
        syncRetryDelay = 3000
      }
    } catch (e) {
      flushPending = true
      syncRetryDelay = 3000
      console.error('会话同步失败:', e)
    } finally {
      syncInFlight = false
      if (flushPending || savedRevision < localRevision) {
        flushPending = false
        syncTimer = setTimeout(flushSyncQueue, syncRetryDelay)
      }
    }
  }

  // 处理服务端返回的同步冲突：拉取服务端副本展示给用户选择，本地未上传内容保留在冲突记录中不丢弃
  async function handleSyncConflicts(conflicts, payload) {
    for (const c of conflicts) {
      if (!c || !c.chatId) continue
      if (c.chatId === '__folders__') {
        // 文件夹定义冲突：拉取服务端最新文件夹合并本地新增后重试（按 id 并集，同名同 id 以服务端为准）
        await mergeFoldersOnConflict()
        continue
      }
      const chatId = c.chatId
      let serverMessages = []
      let serverMeta = null
      let serverVersion = c.serverVersion || 0
      try {
        const res = await loadSingleChatHistory(chatId)
        if (res && res.success) {
          serverMessages = Array.isArray(res.messages) ? res.messages : []
          serverMeta = res.meta || null
          if (res.version) serverVersion = res.version
        }
      } catch (e) { /* 拉取失败仍记录冲突，展示时以已有信息为准 */ }
      const entry = {
        chatId,
        reason: c.reason === 'deleted' ? 'deleted' : 'version',
        serverVersion,
        title: (payload.chatMeta?.[chatId]?.title) || (serverMeta && serverMeta.title) || '',
        // 保留双方数据：本地未上传副本完整保存在冲突记录中，绝不直接丢弃
        localMessages: JSON.parse(JSON.stringify(payload.chats?.[chatId] || chats.value[chatId] || [])),
        localMeta: payload.chatMeta?.[chatId] ? { ...payload.chatMeta[chatId] } : null,
        serverMessages,
        serverMeta
      }
      // 本地视图切换到服务端版本（行仍存在时），本地副本留在冲突记录中待用户决策
      if (c.reason !== 'deleted' && serverMessages.length >= 0) {
        chats.value[chatId] = serverMessages
        if (serverMeta) chatMeta.value[chatId] = { ...serverMeta }
        chatVersions.value[chatId] = serverVersion
      }
      dirtyChatIds.delete(chatId)
      dirtyMetaIds.delete(chatId)
      syncConflicts.value = [...syncConflicts.value.filter(x => x.chatId !== chatId), entry]
    }
  }

  // 文件夹定义冲突合并：以服务端为基准，把服务端不存在的本地文件夹按 id 并入，然后标记重传
  async function mergeFoldersOnConflict() {
    try {
      const data = await loadChatSummaries()
      if (!data || !data.success) return
      const serverFolders = normalizeFolders(data.folders)
      foldersVersion.value = data.foldersVersion || foldersVersion.value
      const serverIds = new Set(serverFolders.map(f => f.id))
      const merged = [...serverFolders]
      folders.value.forEach(f => {
        if (f && f.id && !serverIds.has(f.id)) merged.push({ ...f })
      })
      folders.value = merged
      foldersDirty = true
      syncToServer()
    } catch (e) { /* 合并失败保留下次同步再试 */ }
  }

  // 解决单条同步冲突：useLocal=true 保留本地（显式恢复已删会话），false 采用服务端版本
  function resolveSyncConflict(chatId, useLocal) {
    const entry = syncConflicts.value.find(x => x.chatId === chatId)
    if (!entry) return
    syncConflicts.value = syncConflicts.value.filter(x => x.chatId !== chatId)
    if (!useLocal) return
    // 保留本地：以服务端当前版本为新基准重新上传（已删会话走显式恢复通道）
    if (entry.reason === 'deleted') {
      pendingRestoreIds.add(chatId)
      chatVersions.value[chatId] = 0
      if (chatSummaries.value[chatId]) delete chatSummaries.value[chatId]
      const idx = deletedChatIds.value.indexOf(chatId)
      if (idx >= 0) deletedChatIds.value.splice(idx, 1)
    } else {
      chatVersions.value[chatId] = entry.serverVersion || chatVersions.value[chatId] || 0
    }
    chats.value[chatId] = entry.localMessages
    if (entry.localMeta) chatMeta.value[chatId] = { ...entry.localMeta }
    dirtyChatIds.set(chatId, localRevision + 1)
    syncToServer()
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

  // 发送前同步当前会话：拉取服务端该会话最新记录（其他端可能已新增/修改消息），
  // 以会话版本号判断服务端是否比本地基线新（不再依赖消息条数比对，重新生成/编辑同条数也能识别）；
  // 末尾 pendingCount 条未上传的新消息按稳定消息 id 去重后保留，防止重试产生重复消息
  async function syncCurrentChatFromServer(chatId, pendingCount = 0) {
    const res = await loadSingleChatHistory(chatId)
    if (!res || !res.success || !Array.isArray(res.messages)) return false
    const serverMsgs = res.messages
    const localVer = chatVersions.value[chatId] || 0
    // 版本号可用时以版本为准：服务端版本不高于本地基准则无需合并
    if (res.version && localVer && res.version <= localVer) return false
    const local = chats.value[chatId] || []
    const baseLen = Math.max(0, local.length - pendingCount)
    if (!res.version && serverMsgs.length <= baseLen) return false
    const serverIds = new Set(serverMsgs.map(m => m && m.id).filter(Boolean))
    const pendingTail = local.slice(baseLen).filter(m => !m || !m.id || !serverIds.has(m.id))
    chats.value[chatId] = [...serverMsgs, ...pendingTail]
    if (res.version) chatVersions.value[chatId] = res.version
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
      // 当前会话在其他端有变更（版本号比对，兼容旧服务端回退到条数比对）：合并正文
      const curId = currentChatId.value
      const curSummary = chatSummaries.value[curId]
      if (curSummary) {
        const serverVer = curSummary.version || 0
        const localVer = chatVersions.value[curId] || 0
        const staleByVersion = serverVer > 0 && localVer > 0 && serverVer !== localVer
        const staleByCount = !staleByVersion && (curSummary.count || 0) !== (chats.value[curId] || []).length
        if (staleByVersion || staleByCount) {
          return await syncCurrentChatFromServer(curId, 0)
        }
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
        delete chatVersions.value[id]
        dirtyChatIds.delete(id)
        dirtyMetaIds.delete(id)
        return
      }
      // 其他端更新过的已加载会话踢回未加载态，切换时按需重拉最新正文，
      // 防止本地陈旧副本在下次全量同步时覆盖服务端新内容。
      // 判定依据为服务端会话版本号（识别重新生成/编辑等条数不变的变更）；
      // 旧服务端无版本号时回退到 条数+最后消息时间 比对
      const serverVer = map[id].version || 0
      const localVer = chatVersions.value[id] || 0
      let stale
      if (serverVer > 0 && localVer > 0) {
        stale = serverVer !== localVer
      } else {
        const local = chats.value[id] || []
        let localLast = null
        for (let i = 0; i < local.length; i++) {
          if (local[i].time) localLast = local[i].time
        }
        stale = (map[id].count || 0) !== local.length || (map[id].lastTime || null) !== localLast
      }
      if (stale) {
        delete chats.value[id]
      }
      // 版本号以服务端摘要为准
      if (serverVer > 0) chatVersions.value[id] = serverVer
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
    if (data.foldersVersion) foldersVersion.value = data.foldersVersion
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
    markChatDirty(currentChatId.value)
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
    delete chatVersions.value[id]
    dirtyChatIds.delete(id)
    dirtyMetaIds.delete(id)
    pendingRestoreIds.delete(id)
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

  // 批量删除会话并仅触发一次服务端同步
  function deleteChats(ids) {
    const uniqueIds = [...new Set(Array.isArray(ids) ? ids : [])]
    uniqueIds.forEach(id => {
      delete chats.value[id]
      delete chatSummaries.value[id]
      delete chatMeta.value[id]
      delete chatVersions.value[id]
      dirtyChatIds.delete(id)
      dirtyMetaIds.delete(id)
      pendingRestoreIds.delete(id)
      if (!deletedChatIds.value.includes(id)) deletedChatIds.value.push(id)
    })
    if (uniqueIds.includes(currentChatId.value)) {
      currentChatId.value = findEmptyChatId()
      if (!currentChatId.value) {
        currentChatId.value = Date.now().toString()
        chats.value[currentChatId.value] = []
      }
    }
    syncToServer()
  }

  // 从指定消息位置创建独立会话分支，原会话与后续回答保持不变
  function createBranch(chatId, throughIndex) {
    const source = chats.value[chatId]
    if (!Array.isArray(source)) return null
    const newId = `branch_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`
    chats.value[newId] = JSON.parse(JSON.stringify(source.slice(0, Math.max(0, throughIndex + 1))))
    const oldMeta = chatMeta.value[chatId] || {}
    chatMeta.value[newId] = {
      ...oldMeta,
      title: `${chatTitle(chatId)} · 分支`,
      parentChatId: chatId,
      branchFromIndex: throughIndex
    }
    currentChatId.value = newId
    markChatDirty(newId)
    syncToServer()
    return newId
  }

  function deleteAllChats() {
    const ids = new Set([...Object.keys(chats.value), ...Object.keys(chatSummaries.value)])
    ids.forEach(id => deletedChatIds.value.push(id))
    chats.value = {}
    chatSummaries.value = {}
    chatMeta.value = {}
    chatVersions.value = {}
    dirtyChatIds.clear()
    dirtyMetaIds.clear()
    pendingRestoreIds.clear()
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
    // 为消息分配稳定标识：重试/多端合并时按 id 去重，防止重复消息
    if (!msg.id) msg.id = genMsgId()
    chats.value[chatId].push(msg)
    markChatDirty(chatId)
    syncToServer()
  }

  // 截断会话消息：删除 fromIdx（含）之后的所有消息（用于重新生成/编辑重发）
  function truncateMessages(chatId, fromIdx) {
    const msgs = chats.value[chatId]
    if (!msgs) return
    msgs.splice(fromIdx)
    markChatDirty(chatId)
    syncToServer()
  }

  function updateLastAssistantMessage(chatId, msg) {
    const msgs = chats.value[chatId]
    if (!msgs) return
    const lastIdx = msgs.length - 1
    if (!msg.id) msg.id = genMsgId()
    if (lastIdx >= 0 && msgs[lastIdx].role === 'assistant') {
      msgs[lastIdx] = msg
    } else {
      msgs.push(msg)
    }
    markChatDirty(chatId)
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
      // 从删除列表移除，并声明显式恢复：防止服务端将导入的会话当作已删除而拒绝写入
      const delIdx = deletedChatIds.value.indexOf(id)
      if (delIdx >= 0) deletedChatIds.value.splice(delIdx, 1)
      pendingRestoreIds.add(id)
      markChatDirty(id)
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
      if (foldersChanged) foldersDirty = true
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
    markMetaDirty(id)
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
    markMetaDirty(id)
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
    markMetaDirty(id)
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
    foldersDirty = true
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
    foldersDirty = true
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
        markMetaDirty(chatId)
      }
    })
    foldersDirty = true
    syncToServer()
  }

  // 展开/收起文件夹
  function toggleFolderCollapsed(id) {
    const f = folders.value.find(x => x.id === id)
    if (!f) return
    f.collapsed = !f.collapsed
    foldersDirty = true
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
    markMetaDirty(chatId)
    syncToServer()
  }

  // ========== 回答版本历史 ==========
  // assistant 消息版本结构：msg.versions = [{ versionId, content, reasoning_content,
  // thinkingTime, modelName, time, status, usage }]；msg.currentVersionId 指向当前选中版本。
  // 平铺字段（content/reasoning_content/...）始终保持为当前选中版本的内容，兼容现有渲染/搜索/导出；
  // 旧数据无 versions 字段时读取视为单版本，无需迁移。

  // 生成稳定的版本标识
  function genVersionId() {
    return 'v' + Date.now().toString(36) + Math.random().toString(36).slice(2, 9)
  }

  // 规范化 assistant 消息的版本结构：无 versions 时以平铺字段为初始版本（旧数据无损升级）
  function ensureMsgVersions(msg) {
    if (msg.role !== 'assistant') return null
    if (!Array.isArray(msg.versions) || msg.versions.length === 0) {
      msg.versions = [{
        versionId: msg.currentVersionId || genVersionId(),
        content: msg.content || '',
        reasoning_content: msg.reasoning_content,
        thinkingTime: msg.thinkingTime,
        modelName: msg.modelName,
        time: msg.time,
        status: msg.status || (msg.interrupted ? 'stopped' : 'done'),
        usage: (msg.promptTokens || msg.completionTokens) ? {
          promptTokens: msg.promptTokens || 0,
          completionTokens: msg.completionTokens || 0,
          reasoningTokens: msg.reasoningTokens || 0,
          cachedTokens: msg.cachedTokens || 0
        } : undefined
      }]
      msg.currentVersionId = msg.versions[0].versionId
    }
    return msg.versions
  }

  // 把消息的平铺字段同步为指定版本内容（渲染/复制/朗读/导出/搜索统一读平铺字段）
  function applyVersionToFlatFields(msg, version) {
    msg.content = version.content
    msg.reasoning_content = version.reasoning_content
    msg.thinkingTime = version.thinkingTime
    msg.modelName = version.modelName
    msg.time = version.time
    msg.status = version.status
    msg.interrupted = (version.status && version.status !== 'done') || undefined
    if (version.usage) {
      msg.promptTokens = version.usage.promptTokens
      msg.completionTokens = version.usage.completionTokens
      msg.reasoningTokens = version.usage.reasoningTokens
      msg.cachedTokens = version.usage.cachedTokens
    }
  }

  /**
   * 为 assistant 消息追加一个新回答版本（重新生成用）：
   * 旧版本完整保留；成功完成时新版本成为当前版本，失败/中断时保留部分结果但不改变当前选中版本。
   * @param chatId 会话ID
   * @param msgIdx 消息在会话中的绝对下标
   * @param versionData 新版本数据 { content, reasoning_content, thinkingTime, modelName, time, status, usage }
   * @param makeCurrent 是否设为当前版本（仅成功完成时 true）
   * @returns 新版本 versionId
   */
  function addMessageVersion(chatId, msgIdx, versionData, makeCurrent) {
    const msgs = chats.value[chatId]
    if (!msgs || !msgs[msgIdx] || msgs[msgIdx].role !== 'assistant') return null
    const msg = msgs[msgIdx]
    ensureMsgVersions(msg)
    const versionId = genVersionId()
    msg.versions.push({
      versionId,
      content: versionData.content || '',
      reasoning_content: versionData.reasoning_content,
      thinkingTime: versionData.thinkingTime,
      modelName: versionData.modelName,
      time: versionData.time,
      status: versionData.status || 'done',
      usage: versionData.usage
    })
    if (makeCurrent) {
      msg.currentVersionId = versionId
      applyVersionToFlatFields(msg, msg.versions[msg.versions.length - 1])
    }
    markChatDirty(chatId)
    syncToServer()
    return versionId
  }

  /**
   * 切换 assistant 消息当前展示版本（仅查看，不产生新请求、不重复计费）。
   * @returns true=切换成功
   */
  function selectMessageVersion(chatId, msgIdx, versionId) {
    const msgs = chats.value[chatId]
    if (!msgs || !msgs[msgIdx]) return false
    const msg = msgs[msgIdx]
    const versions = ensureMsgVersions(msg)
    const target = versions.find(v => v.versionId === versionId)
    if (!target || msg.currentVersionId === versionId) return false
    msg.currentVersionId = versionId
    applyVersionToFlatFields(msg, target)
    markChatDirty(chatId)
    syncToServer()
    return true
  }

  /**
   * 获取消息的版本视图信息（UI 切换器用）：
   * { list: [{versionId, time, modelName, status}], currentIndex, total }
   */
  function getMessageVersions(chatId, msgIdx) {
    const msgs = chats.value[chatId]
    if (!msgs || !msgs[msgIdx]) return null
    const msg = msgs[msgIdx]
    if (msg.role !== 'assistant') return null
    const versions = ensureMsgVersions(msg)
    const currentIndex = versions.findIndex(v => v.versionId === msg.currentVersionId)
    return {
      list: versions.map(v => ({ versionId: v.versionId, time: v.time, modelName: v.modelName, status: v.status })),
      currentIndex: currentIndex < 0 ? 0 : currentIndex,
      total: versions.length
    }
  }

  // 将多个会话批量移入同一文件夹并只同步一次
  function moveChatsToFolder(chatIds, folderId) {
    const valid = !folderId || folders.value.some(folder => folder.id === folderId)
    if (!valid) return
    ;[...new Set(Array.isArray(chatIds) ? chatIds : [])].forEach(chatId => {
      const meta = chatMeta.value[chatId] || (chatMeta.value[chatId] = {})
      if (folderId) meta.folderId = folderId
      else delete meta.folderId
      markMetaDirty(chatId)
    })
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
    markMetaDirty(id)
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
    folders, chatVersions, foldersVersion, syncConflicts,
    sortedChatList, currentMessages,
    loadFromServer, syncToServer, suspendSync, resumeSync, syncCurrentChatFromServer, refreshFromServer,
    ensureChatLoaded, ensureAllChatsLoaded, resolveSyncConflict, genMsgId,
    addMessageVersion, selectMessageVersion, getMessageVersions,
    newChat, switchChat, switchChatLazy, deleteChat, deleteChats, deleteAllChats, createBranch,
    addMessage, truncateMessages, updateLastAssistantMessage, exportChats, exportChatsJson, importChatsJson, countValidChats, findEmptyChatId,
    togglePin, renameChat, setAutoTitleIfEmpty, exportChatMarkdown, setChatPromptPreset, chatTitle,
    createFolder, renameFolder, deleteFolder, toggleFolderCollapsed, moveChatToFolder, moveChatsToFolder
  }
})
