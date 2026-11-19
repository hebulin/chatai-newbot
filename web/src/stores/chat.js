import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { loadChatHistory, loadChatSummaries, loadChatVersion, saveChatHistory, loadSingleChatHistory, restoreTrash } from '@/api/chat'
import { buildChatListProjection, normalizeChatFolders as normalizeFolders } from '@/stores/chat/chatListProjection'
import { downloadAllChats, downloadChatsJson, downloadSingleChatMarkdown, prepareChatImport } from '@/stores/chat/chatArchive'
import { appendMessageVersion, getMessageVersionView, selectMessageVersion as selectVersion } from '@/stores/chat/messageVersions'
import { createRecoveryKey, createSyncPayload, persistSyncRecovery, readSyncRecovery } from '@/stores/chat/chatSyncState'

export const useChatStore = defineStore('chat', () => {
  const chats = ref({})
  // 未加载会话的摘要（id -> { title, preview, lastTime, count }）：
  // 懒加载模式下首屏只拉摘要，会话正文切换时才按需加载进 chats
  const chatSummaries = ref({})
  const chatMeta = ref({})
  const currentChatId = ref(null)
  const deletedChatIds = ref([])
  // 每个被删会话删除时本地已知的行版本（id -> version）：服务端据此忽略
  // “恢复前产生、恢复后才到达”的陈旧删除声明，避免已恢复会话被其他端重新软删除
  const deletedChatIdVersions = ref({})
  // 恢复响应返回的行版本（仅用于随后删除时的版本基准，不抬高已加载会话的正文基准）
  const restoredDeleteVersions = ref({})
  // 刚恢复的空会话ID：空会话默认不进侧边栏，恢复后临时放行以便用户找到它
  const restoredEmptyChatIds = ref(new Set())
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
  // 强制刷新待执行标记：回收站恢复等绕过同步协议的变更若遇同步/刷新繁忙，
  // 先记录待守卫解除后补一次；不能只依赖版本号检测，本地保存会推进 remoteVersion 掩盖变更
  let forceRefreshPending = false
  // 本地状态代次：回收站恢复等绕过同步协议的本地变更递增，供刷新请求识别过期快照
  let localStateEpoch = 0
  // 强制刷新请求序号：每次显式 force 调用递增，防止在途旧刷新消费后来者的待执行标记
  let forceRefreshSeq = 0
  // 服务端摘要版本连续低于本地已知版本的次数：连续多次视为服务端序列号真实回退，收敛基准避免永久停摆
  let staleVersionCount = 0
  // 当前内存状态所属账号与状态代次：账号切换时整体清空，异步响应按代次丢弃，防止跨账号串数据
  let stateUsername = null
  let userStateGeneration = 0
  // 在途历史加载令牌集合：并发/二次加载时只有全部结束才算加载完成
  const pendingHistoryLoads = new Set()
  let historyLoadToken = 0

  // 当前账号名（与恢复草稿键同一来源）；存储被禁用时按匿名处理
  function currentUsername() {
    try {
      return localStorage.getItem('username') || 'anonymous'
    } catch {
      return 'anonymous'
    }
  }

  /**
   * 账号切换时整体清空内存中的用户态与同步状态。
   * 本地恢复草稿按用户名隔离，清空后由 restoreRecoveryDraft 重新合并当前账号的草稿；
   * 递增状态代次使在途请求的迟到响应不会写回新账号。
   */
  function resetUserState() {
    userStateGeneration++
    chats.value = {}
    chatSummaries.value = {}
    chatMeta.value = {}
    currentChatId.value = null
    deletedChatIds.value = []
    deletedChatIdVersions.value = {}
    restoredDeleteVersions.value = {}
    chatVersions.value = {}
    folders.value = []
    foldersVersion.value = 0
    syncConflicts.value = []
    restoredEmptyChatIds.value = new Set()
    dirtyChatIds.clear()
    dirtyMetaIds.clear()
    pendingRestoreIds.clear()
    foldersDirty = false
    localRevision = 0
    savedRevision = 0
    flushPending = false
    syncRetryDelay = 0
    remoteVersion = 0
    forceRefreshPending = false
    forceRefreshSeq = 0
    staleVersionCount = 0
    refreshing = false
    syncInFlight = false
    syncSuspended = false
    pendingSyncWhileSuspended = false
    pendingHistoryLoads.clear()
    isChatHistoryLoaded.value = false
    if (syncTimer) {
      clearTimeout(syncTimer)
      syncTimer = null
    }
  }

  // 当前账号的未同步恢复快照键，避免不同用户之间读取彼此草稿
  function recoveryKey() {
    return createRecoveryKey(localStorage)
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

  /**
   * 记录本地删除声明及删除时已知行版本。
   * 已删除的会话只允许版本上升，避免重复删除把正版本降级为 0 而被服务端误判为陈旧声明。
   * @param {string} id 会话ID
   * @param {number} version 删除时本地已知的服务端行版本
   */
  function recordDeletedChat(id, version) {
    // 恢复响应返回的行版本优先：恢复后立即删除时本地正文基准可能仍是恢复前旧值
    const effectiveVersion = Math.max(version || 0, restoredDeleteVersions.value[id] || 0)
    if (deletedChatIds.value.includes(id)) {
      if (effectiveVersion > (deletedChatIdVersions.value[id] || 0)) {
        deletedChatIdVersions.value[id] = effectiveVersion
      }
      return
    }
    deletedChatIds.value.push(id)
    deletedChatIdVersions.value[id] = effectiveVersion
  }

  // 生成当前增量保存载荷：仅包含脏会话/脏元信息，携带每会话基准版本供服务端乐观锁校验
  function buildSyncPayload() {
    return createSyncPayload({
      chats: chats.value,
      chatMeta: chatMeta.value,
      deletedChatIds: deletedChatIds.value,
      deletedChatIdVersions: deletedChatIdVersions.value,
      folders: folders.value,
      chatVersions: chatVersions.value,
      foldersVersion: foldersVersion.value,
      currentChatId: currentChatId.value,
      dirtyChatIds,
      dirtyMetaIds,
      foldersDirty,
      pendingRestoreIds,
      remoteVersion
    })
  }

  // 写入刷新/断网恢复快照；保存成功且无后续变更时才清除
  function persistRecoveryDraft(payload) {
    try {
      persistSyncRecovery(localStorage, recoveryKey(), localRevision, {
        ...payload, recoveryConflicts: syncConflicts.value
      })
    } catch { /* 浏览器可能在访问 localStorage 属性时即抛错，不能中断服务器保存 */ }
  }

  // 将尚未成功上传的本地恢复快照合并进服务端摘要状态
  function restoreRecoveryDraft() {
    try {
      const draft = readSyncRecovery(localStorage, recoveryKey())
      if (!draft) return
      const payload = draft.payload
      localRevision = Math.max(localRevision, Number(draft.revision || 1))
      syncConflicts.value = Array.isArray(payload.recoveryConflicts) ? payload.recoveryConflicts : []
      // 草稿必须沿用写入时的基准版本；缺少基准的旧草稿使用 0，让已有会话走冲突处理。
      const restoreBaseVersion = id => {
        const version = payload.baseVersions?.[id]
        chatVersions.value[id] = Number.isSafeInteger(version) && version >= 0 ? version : 0
      }
      Object.entries(payload.chats || {}).forEach(([id, messages]) => {
        if (Array.isArray(messages)) {
          chats.value[id] = messages
          restoreBaseVersion(id)
          // 恢复的草稿属于未上传变更，标记为脏数据等待重新同步
          dirtyChatIds.set(id, localRevision + 1)
        }
      })
      chatMeta.value = { ...chatMeta.value, ...(payload.chatMeta || {}) }
      Object.keys(payload.chatMeta || {}).forEach(id => {
        restoreBaseVersion(id)
        if (!dirtyChatIds.has(id)) dirtyMetaIds.set(id, localRevision + 1)
      })
      deletedChatIds.value = [...new Set([...deletedChatIds.value, ...(payload.deletedChatIds || [])])]
      // 合并草稿中的删除版本：保留已知的正版本，缺省项记为未知（0）
      const mergedDeletedVersions = { ...deletedChatIdVersions.value }
      const draftDeletedVersions = payload.deletedChatIdVersions && typeof payload.deletedChatIdVersions === 'object'
        ? payload.deletedChatIdVersions : {}
      Object.entries(draftDeletedVersions).forEach(([id, version]) => {
        if (typeof version === 'number' && version > 0) {
          mergedDeletedVersions[id] = Math.max(mergedDeletedVersions[id] || 0, version)
        } else if (mergedDeletedVersions[id] === undefined) {
          mergedDeletedVersions[id] = 0
        }
      })
      deletedChatIds.value.forEach(id => {
        if (mergedDeletedVersions[id] === undefined) mergedDeletedVersions[id] = 0
      })
      deletedChatIdVersions.value = mergedDeletedVersions
      if (Array.isArray(payload.folders)) {
        folders.value = normalizeFolders(payload.folders)
        foldersVersion.value = Number.isSafeInteger(payload.baseFoldersVersion) ? payload.baseFoldersVersion : 0
        foldersDirty = true
      }
      if (payload.lastChatId) currentChatId.value = payload.lastChatId
      flushPending = true
    } catch (e) { /* 损坏草稿忽略，服务端数据仍可使用 */ }
  }

  // 按日期分组的会话列表（投影逻辑独立，Store 仅提供响应式状态）
  const sortedChatList = computed(() => buildChatListProjection({
    chats: chats.value,
    chatSummaries: chatSummaries.value,
    chatMeta: chatMeta.value,
    folders: folders.value,
    searchKeyword: searchKeyword.value,
    restoredEmptyChatIds: restoredEmptyChatIds.value
  }))

  const currentMessages = computed(() => {
    return chats.value[currentChatId.value] || []
  })

  // 从服务端加载会话列表（只拉摘要，不含消息内容），再按需加载上次打开的会话
  async function loadFromServer() {
    // 同一 SPA 内换账号时 store 不会重建：先按用户名识别并清空上一账号的全部用户态，
    // 再合并当前账号按用户名隔离的恢复草稿，避免正文/冲突/脏数据跨账号泄露或串写
    const username = currentUsername()
    if (stateUsername !== null && stateUsername !== username) resetUserState()
    stateUsername = username
    const generation = userStateGeneration
    // 二次进入聊天页时也必须等待所有在途加载完成，恢复流程不能与加载快照并发
    const loadToken = ++historyLoadToken
    pendingHistoryLoads.add(loadToken)
    const finishLoad = () => pendingHistoryLoads.delete(loadToken)
    let historyLoadFailed = false
    try {
      const data = await loadChatSummaries()
      if (generation !== userStateGeneration) { finishLoad(); return }
      if (data && data.success) {
        chats.value = {}
        chatMeta.value = data.chatMeta || {}
        deletedChatIds.value = data.deletedChatIds || []
        // 服务端删除列表本身不带逐项版本：先记为未知（0），
        // 后续 restoreRecoveryDraft 会用草稿中已知的正版本覆盖
        deletedChatIdVersions.value = {}
        deletedChatIds.value.forEach(id => { deletedChatIdVersions.value[id] = 0 })
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
          if (generation !== userStateGeneration) { finishLoad(); return }
          currentChatId.value = lastId
        } else {
          newChat()
        }
      } else {
        // 摘要读取失败不能当作“空列表”：先合并本地恢复草稿，保留未上传内容；
        // 否则后续任意保存都会用空状态覆盖草稿，丢失离线消息/删除等修改
        historyLoadFailed = true
        restoreRecoveryDraft()
        if (!currentChatId.value) newChat()
      }
    } catch (e) {
      if (generation !== userStateGeneration) { finishLoad(); return }
      console.error('加载会话历史失败:', e)
      historyLoadFailed = true
      restoreRecoveryDraft()
      if (!currentChatId.value) newChat()
    }
    if (generation !== userStateGeneration) { finishLoad(); return }
    if (historyLoadFailed) {
      // 安排强制重拉：草稿若存在会在 flush 后补拉，否则立即重试；版本基准归零避免被跳过
      forceRefreshPending = true
      remoteVersion = 0
    }
    finishLoad()
    isChatHistoryLoaded.value = true
    if (flushPending) syncToServer()
    else if (forceRefreshPending) refreshFromServer()
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
    // 正文懒加载不能清除尚未同步的重命名/置顶，也不能把它的旧版本基准更新为最新值。
    if (res.version && !dirtyMetaIds.has(id) && !dirtyChatIds.has(id)) chatVersions.value[id] = res.version
    if (res.meta && typeof res.meta === 'object' && !dirtyMetaIds.has(id)) {
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
    const generation = userStateGeneration
    const revision = localRevision
    const payload = buildSyncPayload()
    syncInFlight = true
    flushPending = false
    try {
      const res = await saveChatHistory(payload)
      // 账号已切换：旧账号请求的响应不得写回新账号状态
      if (generation !== userStateGeneration) return
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
        // 服务端忽略了陈旧删除声明（该会话已被其他端恢复）：立即解除本地删除态并
        // 安排强制对账，避免用户本地一直隐藏该会话、直到下次轮询才突然出现
        if (Array.isArray(res.ignoredDeleteIds) && res.ignoredDeleteIds.length > 0) {
          const ignored = new Set(res.ignoredDeleteIds.filter(id => typeof id === 'string' && id.length > 0))
          if (ignored.size > 0) {
            deletedChatIds.value = deletedChatIds.value.filter(id => !ignored.has(id))
            ignored.forEach(id => { delete deletedChatIdVersions.value[id] })
            forceRefreshPending = true
          }
        }
        if (typeof res.foldersVersion === 'number') {
          foldersVersion.value = res.foldersVersion
          if (payload.folders !== undefined) foldersDirty = false
        } else if (payload.folders !== undefined) {
          foldersDirty = false
        }
        ;(payload.restoreChatIds || []).forEach(id => pendingRestoreIds.delete(id))
        await loadRestoredMetadataChats(payload, res.versions || {})
        // 版本冲突处理：服务端未覆盖的数据在此合并，产生用户可见的冲突入口
        if (Array.isArray(res.conflicts) && res.conflicts.length > 0) {
          await handleSyncConflicts(res.conflicts, payload)
        }
        // 未决冲突也属于尚未保存的数据；必须保留到用户明确选择，刷新不能丢失本地副本。
        if (savedRevision === localRevision && syncConflicts.value.length === 0) {
          try { localStorage.removeItem(recoveryKey()) } catch (e) { /* ignore */ }
        } else {
          persistRecoveryDraft(buildSyncPayload())
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
      // 账号已切换时旧请求不再管理新账号的同步标志/定时器（resetUserState 已重置）
      if (generation !== userStateGeneration) return
      syncInFlight = false
      if (flushPending || savedRevision < localRevision) {
        flushPending = false
        syncTimer = setTimeout(flushSyncQueue, syncRetryDelay)
      } else if (forceRefreshPending) {
        // 同步空闲后补执行回收站恢复等强制刷新，避免本地保存推进版本号后漏刷新侧边栏
        refreshFromServer()
      }
    }
  }

  /** 纯元信息恢复没有本地正文；直接重拉恢复后的行，使会话立即回到列表和聊天区。 */
  async function loadRestoredMetadataChats(payload, versions) {
    const generation = userStateGeneration
    for (const id of payload.restoreChatIds || []) {
      if (Object.hasOwn(payload.chats || {}, id) || versions[id] === undefined) continue
      try {
        const data = await loadSingleChatHistory(id)
        // 账号已切换：旧账号的补拉结果不得写入新账号
        if (generation !== userStateGeneration) return
        // 用户可能在补拉期间再次删除；迟到的读取响应不能让已删除会话在本地复活。
        if (deletedChatIds.value.includes(id)) continue
        if (!data?.success || data.exists === false) {
          remoteVersion = 0 // 下次自动同步重新拉摘要，不能把缺失的本地列表视为已经同步。
          continue
        }
        if (!dirtyChatIds.has(id)) chats.value[id] = Array.isArray(data.messages) ? data.messages : []
        if (!dirtyMetaIds.has(id) && data.meta) chatMeta.value[id] = { ...data.meta }
      } catch {
        remoteVersion = 0
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
        hasLocalMessages: Object.hasOwn(payload.chats || {}, chatId),
        localMessages: Object.hasOwn(payload.chats || {}, chatId)
          ? JSON.parse(JSON.stringify(payload.chats[chatId])) : null,
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
      foldersVersion.value = typeof data.foldersVersion === 'number'
        ? data.foldersVersion : foldersVersion.value
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
    if (!useLocal) {
      persistRecoveryDraft(buildSyncPayload())
      return
    }
    // 保留本地：以服务端当前版本为新基准重新上传（已删会话走显式恢复通道）
    if (entry.reason === 'deleted') {
      pendingRestoreIds.add(chatId)
      chatVersions.value[chatId] = 0
      if (chatSummaries.value[chatId]) delete chatSummaries.value[chatId]
      const idx = deletedChatIds.value.indexOf(chatId)
      if (idx >= 0) deletedChatIds.value.splice(idx, 1)
      delete deletedChatIdVersions.value[chatId]
    } else {
      chatVersions.value[chatId] = entry.serverVersion || chatVersions.value[chatId] || 0
    }
    if (entry.hasLocalMessages !== false) {
      chats.value[chatId] = entry.localMessages || []
      dirtyChatIds.set(chatId, localRevision + 1)
    } else {
      dirtyMetaIds.set(chatId, localRevision + 1)
    }
    if (entry.localMeta) chatMeta.value[chatId] = { ...entry.localMeta }
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
    } else if (forceRefreshPending) {
      // 挂起期间被挡下的强制刷新（如回收站恢复），解除后立即补拉摘要
      refreshFromServer()
    }
  }

  // 发送前同步当前会话：拉取服务端该会话最新记录（其他端可能已新增/修改消息），
  // 以会话版本号判断服务端是否比本地基线新（不再依赖消息条数比对，重新生成/编辑同条数也能识别）；
  // 末尾 pendingCount 条未上传的新消息按稳定消息 id 去重后保留，防止重试产生重复消息
  async function syncCurrentChatFromServer(chatId, pendingCount = 0) {
    const generation = userStateGeneration
    const requestStateEpoch = localStateEpoch
    const res = await loadSingleChatHistory(chatId)
    if (generation !== userStateGeneration) return false
    if (!res || !res.success || !Array.isArray(res.messages)) return false
    // 响应返回前该会话已被删除，或发生回收站恢复（正文可能已变化）：丢弃迟到响应。
    // 不使用全局 localRevision：切换会话/修改其他会话不应让本会话的有效正文合并失效。
    if (deletedChatIds.value.includes(chatId)
      || localStateEpoch !== requestStateEpoch) return false
    // 刷新路径（pendingCount=0）遇到本会话有未上传本地修改时放弃合并，
    // 避免请求期间新增/编辑的消息被服务端快照覆盖；发送路径用 pendingCount 保留尾部消息。
    if (pendingCount === 0 && (dirtyChatIds.has(chatId) || dirtyMetaIds.has(chatId))) return false
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
  // options.force=true 时忽略版本号相等判断直接拉取摘要（回收站恢复等绕过同步协议的变更使用）。
  // 返回当前会话正文是否被合并更新（调用方据此决定是否贴底滚动）
  async function refreshFromServer(options = {}) {
    // 显式强制刷新代表一次新的刷新需求，先递增序号；只有本次请求开始后没有更新的
    // 强制请求时，才允许在成功应用后消费待执行标记，避免在途旧刷新吞掉后到需求。
    if (options.force === true) forceRefreshSeq++
    const force = options.force === true || forceRefreshPending
    if (!isChatHistoryLoaded.value || syncSuspended || syncTimer || syncInFlight || refreshing) {
      // 强制刷新遇忙时记录待执行标记，待同步/挂起状态解除后补一次
      if (force) forceRefreshPending = true
      return false
    }
    // 记录请求发出时的本地修订号、本地状态代次与已知服务端版本：请求期间完成的本地
    // 保存或回收站恢复会让摘要响应变成旧快照，直接应用会覆盖刚删除/刚恢复的本地状态（TOCTOU）。
    const startRevision = localRevision
    const startStateEpoch = localStateEpoch
    const startRemoteVersion = remoteVersion
    const requestForceSeq = forceRefreshSeq
    const generation = userStateGeneration
    // 本次请求是否已成功应用摘要（此后当前会话正文合并失败不再需要重拉摘要）
    let summaryApplied = false
    // 是否抑制 finally 的立即补执行：网络失败/服务端快照版本异常时避免热循环，
    // 保留待执行标记交给下一轮轮询/保存/挂起解除；有更新的强制请求时例外。
    let suppressImmediateRetry = false
    refreshing = true
    try {
      let checkedVersion = startRemoteVersion
      if (!force) {
        const v = await loadChatVersion()
        if (generation !== userStateGeneration) return false
        // 0 也是合法版本（旧备份恢复后 sync_seq 可能为 0），不能用 !v.version 排除
        if (!v || !v.success || typeof v.version !== 'number' || v.version === remoteVersion) return false
        checkedVersion = v.version
      }
      const data = await loadChatSummaries()
      if (generation !== userStateGeneration) return false
      if (!data || !data.success) {
        // 强制刷新失败：保留待执行标记，下一次刷新/补执行时重试，避免一次瞬时失败永久漏刷新
        if (force) {
          forceRefreshPending = true
          suppressImmediateRetry = true
        }
        return false
      }
      // 拉取期间本地可能产生了新变更，本轮放弃，避免服务端旧快照覆盖本地
      if (syncSuspended || syncTimer || syncInFlight) {
        if (force) forceRefreshPending = true
        return false
      }
      // 本地已完成过保存（修订号变化）或发生回收站恢复等绕过同步协议的变更（状态代次变化）：
      // 该摘要已是旧快照，放弃本轮并立即补拉，防止已删除会话复活或已恢复会话被重新隐藏。
      if (localRevision !== startRevision || localStateEpoch !== startStateEpoch) {
        forceRefreshPending = true
        return false
      }
      // 仅服务端版本领先于快照（代理缓存/只读副本滞后等）：保留待执行标记但不立即
      // 热重试，避免版本持续异常时形成请求风暴，交给下一轮轮询或保存完成时重试。
      // 连续两次仍低于本地基准时视为服务端序列号真实回退（备份重建等），收敛基准后重拉，
      // 避免永久停留在“只拉取不应用”的状态。
      if (typeof data.version === 'number' && remoteVersion > data.version) {
        staleVersionCount++
        if (staleVersionCount >= 2) {
          console.warn('服务端同步版本低于本地基准，收敛后重拉', {
            localVersion: remoteVersion, serverVersion: data.version
          })
          remoteVersion = data.version
          staleVersionCount = 0
          forceRefreshPending = true
          return false
        }
        forceRefreshPending = true
        suppressImmediateRetry = true
        return false
      }
      staleVersionCount = 0
      applyServerState(data)
      summaryApplied = true
      // 成功应用后才消费强制标记（且本次请求开始后没有新的强制刷新需求）；
      // 旧服务端缺 version 时回退到版本检查结果；0 是合法版本，必须原样落库
      if (typeof data.version === 'number') remoteVersion = data.version
      else remoteVersion = checkedVersion || remoteVersion
      if (forceRefreshSeq === requestForceSeq) forceRefreshPending = false
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
      if (generation !== userStateGeneration) return false
      console.error('会话列表自动同步失败:', e)
      // 摘要尚未应用时的强制刷新失败保留标记，避免本地后续保存推进版本号后原问题复现；
      // 摘要已应用时侧边栏已更新，当前会话正文合并失败不再触发整轮重拉。
      if (force && !summaryApplied) {
        forceRefreshPending = true
        suppressImmediateRetry = true
      }
      return false
    } finally {
      // 账号已切换：旧请求不再管理新账号的 refreshing/pending 与补执行
      if (generation !== userStateGeneration) return
      refreshing = false
      // 被守卫挡下/快照过期且可安全重试时立即补执行；若本次失败但期间已有更新的
      // 强制请求，也立即补执行一次，避免延后的恢复展示要等 30 秒轮询。
      const hasNewerForceRequest = forceRefreshSeq !== requestForceSeq
      if ((!suppressImmediateRetry || hasNewerForceRequest)
        && forceRefreshPending && !syncSuspended && !syncTimer && !syncInFlight) {
        Promise.resolve().then(() => refreshFromServer())
      }
    }
  }

  // 将服务端摘要状态合并到本地（仅在本地无待上传变更时调用，服务端为准）
  function applyServerState(data) {
    const map = {}
    ;(data.summaries || []).forEach(s => { if (s && s.id) map[s.id] = s })
    // 已删除列表以服务端为准：本地删除列表可能滞后于服务端（如用户在回收站恢复会话后），
    // 继续用本地旧列表过滤会把刚恢复的会话重新隐藏，导致侧边栏必须刷新页面才显示。
    // 本函数仅由 refreshFromServer 在本地无待上传变更、且响应快照未过期时调用，
    // 服务端状态即权威状态；兼容旧服务端未返回该字段时回退本地列表，
    // 且末尾也不覆盖本地列表，保持“本地已删除的会话不复活”语义。
    const hasServerDeletedIds = Array.isArray(data.deletedChatIds)
    const serverDeletedIds = hasServerDeletedIds ? data.deletedChatIds : deletedChatIds.value
    serverDeletedIds.forEach(id => { delete map[id] })

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

    // 只给“未加载正文”的摘要会话补版本基准：恢复的会话通常未加载，缺少版本号时
    // 直接置顶/重命名会以 0 为基准校验已存在行，触发假版本冲突。
    // 已加载会话（尤其当前会话）的版本由上方合并逻辑/正文同步维护，不能提前抬高，
    // 否则会跳过其他端更新的版本判定并让本地保存以新基准覆盖服务端内容。
    Object.keys(map).forEach(id => {
      if (id === currentChatId.value || chats.value[id] !== undefined) return
      const version = map[id].version || 0
      if (version > 0) chatVersions.value[id] = version
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
    if (typeof data.foldersVersion === 'number') foldersVersion.value = data.foldersVersion
    // 已删除列表以服务端累积合并后的为准；旧服务端缺该字段时保留本地列表
    // （与上方过滤使用同一份 serverDeletedIds，避免“本轮隐藏、下轮复活”）
    deletedChatIds.value = hasServerDeletedIds ? data.deletedChatIds : serverDeletedIds
    // 删除版本与删除列表对齐：保留本地已知的正版本，其余记为未知（0）；
    // 已恢复（不再出现在删除列表）的会话同步移除版本记录。
    const nextDeletedVersions = {}
    deletedChatIds.value.forEach(id => {
      const knownVersion = deletedChatIdVersions.value[id]
      nextDeletedVersions[id] = typeof knownVersion === 'number' && knownVersion > 0 ? knownVersion : 0
    })
    deletedChatIdVersions.value = nextDeletedVersions
  }

  /**
   * 回收站恢复前/后的本地状态同步：立即从本地删除列表移除目标会话。
   * 恢复接口绕过会话同步协议直接改服务端，若本地删除列表未及时清理，
   * 恢复前已构建的在途保存载荷或恢复后新产生的保存都会把会话再当作删除项上传，
   * 导致服务端重新软删除。因此在发送恢复请求前就应先调用本方法乐观清理。
   * @param {string[]} restoredIds 待恢复/已恢复的会话ID列表
   */
  function applyTrashRestore(restoredIds) {
    if (!Array.isArray(restoredIds) || restoredIds.length === 0) return
    const restored = new Set(restoredIds)
    // 递增本地状态代次：让恢复前已发出的摘要请求在返回时识别为过期快照并丢弃
    localStateEpoch++
    deletedChatIds.value = deletedChatIds.value.filter(id => !restored.has(id))
    restored.forEach(id => { delete deletedChatIdVersions.value[id] })
    // 不清除 pendingRestoreIds：冲突解决“保留本地”产生的显式恢复声明仍需随下次保存上传，
    // 提前清除会让该会话以 0 为基准写入已存在行，误报版本冲突；重复声明交给服务端幂等处理。
    // 若存在本地恢复草稿，只从草稿的删除列表中剔除已恢复会话，保留草稿内未上传的
    // 正文/元信息/文件夹/基准版本与 revision；整体重写会覆盖另一标签页或尚未合并的离线改动。
    try {
      const draft = readSyncRecovery(localStorage, recoveryKey())
      if (draft?.payload) {
        let changed = false
        if (Array.isArray(draft.payload.deletedChatIds)) {
          const nextDeleted = draft.payload.deletedChatIds.filter(id => !restored.has(id))
          if (nextDeleted.length !== draft.payload.deletedChatIds.length) {
            draft.payload.deletedChatIds = nextDeleted
            changed = true
          }
        }
        if (draft.payload.deletedChatIdVersions && typeof draft.payload.deletedChatIdVersions === 'object') {
          restored.forEach(id => {
            if (Object.hasOwn(draft.payload.deletedChatIdVersions, id)) {
              delete draft.payload.deletedChatIdVersions[id]
              changed = true
            }
          })
        }
        if (changed) persistSyncRecovery(localStorage, recoveryKey(), draft.revision, draft.payload)
      }
    } catch { /* 浏览器禁用存储时忽略，服务器保存不受影响 */ }
  }

  /**
   * 回收站恢复成功后清理同一会话的未决同步冲突：
   * 冲突记录保留的是恢复前的本地副本，继续保留会让用户后续选择“保留本地”覆盖恢复后的正文。
   * @param {string[]} restoredIds 已确认恢复的会话ID
   */
  function resolveRestoredConflicts(restoredIds) {
    if (!Array.isArray(restoredIds) || restoredIds.length === 0) return
    const restored = new Set(restoredIds)
    if (syncConflicts.value.some(entry => restored.has(entry.chatId))) {
      syncConflicts.value = syncConflicts.value.filter(entry => !restored.has(entry.chatId))
    }
    try {
      const draft = readSyncRecovery(localStorage, recoveryKey())
      const recoveryConflicts = draft?.payload?.recoveryConflicts
      if (draft?.payload && Array.isArray(recoveryConflicts)) {
        const next = recoveryConflicts.filter(entry => !restored.has(entry.chatId))
        if (next.length !== recoveryConflicts.length) {
          draft.payload.recoveryConflicts = next
          persistSyncRecovery(localStorage, recoveryKey(), draft.revision, draft.payload)
        }
      }
    } catch { /* 浏览器禁用存储时忽略 */ }
  }

  /**
   * 等待首屏历史加载并合并本地恢复草稿完成。回收站恢复必须等加载结束，
   * 否则空 store 状态会覆盖尚未合并的离线草稿，或与加载快照互相覆盖。
   * @param {number} timeoutMs 最长等待毫秒数
   * @returns {Promise<boolean>} 是否在超时前完成加载
   */
  async function waitForHistoryLoaded(timeoutMs = 5000) {
    const deadline = Date.now() + Math.max(0, timeoutMs)
    while (pendingHistoryLoads.size > 0 || !isChatHistoryLoaded.value) {
      if (Date.now() >= deadline) return false
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    return true
  }

  /**
   * 等待本地会话同步进入空闲（无 500ms 防抖待上传、无上传中请求）。
   * 回收站恢复等绕过同步协议的写操作必须先与同步串行，避免恢复前已构建的
   * 保存载荷在恢复提交后到达服务端，把刚恢复的会话重新软删除。
   * 注意：不等待 syncSuspended（挂起期间不会发出新载荷，恢复后新载荷会使用清理过的删除列表）。
   * @param {number} timeoutMs 最长等待毫秒数，超时后返回 false（调用方仍可继续，但存在竞态）
   * @returns {Promise<boolean>} 是否在超时前进入空闲
   */
  async function waitForSyncIdle(timeoutMs = 5000) {
    const deadline = Date.now() + Math.max(0, timeoutMs)
    while (syncTimer || syncInFlight) {
      if (Date.now() >= deadline) return false
      await new Promise(resolve => setTimeout(resolve, 50))
    }
    return true
  }

  /**
   * 从回收站恢复会话（加载/同步串行化 + 乐观清理 + 自包含对账）。
   * 恢复接口绕过会话同步协议：必须在首屏历史加载完成、同步空闲后再提交，
   * 否则可能覆盖本地恢复草稿，或被在途保存载荷重新软删除。
   * 恢复结果确认后由 store 直接强制刷新摘要，即使弹窗在请求期间关闭也不会漏更新侧边栏。
   * @param {string[]} ids 待恢复会话ID列表
   * @returns {Promise<{success: boolean, restored?: string[], message?: string, busy?: boolean}>} 服务端响应
   */
  async function restoreChatsFromTrash(ids) {
    const targetIds = [...new Set((Array.isArray(ids) ? ids : [])
      .filter(id => typeof id === 'string' && id.length > 0))]
    if (targetIds.length === 0) return { success: true, restored: [] }
    const generation = userStateGeneration
    // 首屏历史未加载完成时恢复会与加载快照/草稿合并互相覆盖，明确返回 busy 让界面提示重试
    if (!await waitForHistoryLoaded()) return { success: false, busy: true }
    if (generation !== userStateGeneration) return { success: false, busy: true }
    // 上传迟迟未结束时不继续恢复：在途旧载荷晚于恢复到达服务端会重新软删除会话，
    // 明确返回 busy 让界面提示用户稍后重试，而不是静默制造竞态。
    if (!await waitForSyncIdle()) return { success: false, busy: true }
    if (generation !== userStateGeneration) return { success: false, busy: true }
    applyTrashRestore(targetIds)
    try {
      const res = await restoreTrash(targetIds)
      if (generation !== userStateGeneration) return { success: false, unknown: true }
      if (res?.success) {
        const restored = Array.isArray(res.restored) ? res.restored : []
        // 写入服务端返回的恢复后行版本：未加载会话可直接作为正文基准；
        // 已加载/当前会话只记录删除基准，不能提前抬高 chatVersions，
        // 否则后续正文合并会因版本相等被跳过，旧正文可能以新基准覆盖恢复内容。
        if (res.versions && typeof res.versions === 'object') {
          restored.forEach(id => {
            const version = res.versions[id]
            if (typeof version !== 'number' || version <= 0) return
            restoredDeleteVersions.value[id] = version
            if (chats.value[id] === undefined) chatVersions.value[id] = version
          })
        }
        if (restored.length > 0) {
          applyTrashRestore(restored)
          // 空会话默认不进入侧边栏：恢复的ID临时放行，使空会话也能立即出现在列表中
          const emptyIds = new Set(restoredEmptyChatIds.value)
          restored.forEach(id => emptyIds.add(id))
          restoredEmptyChatIds.value = emptyIds
        }
        // 恢复已确认（含“服务端返回空但实际已被其他端恢复”）：清理同一会话的旧冲突记录
        resolveRestoredConflicts(restored.length > 0 ? restored : targetIds)
        // 恢复接口绕过同步协议，store 内部立即以服务端为准对账（弹窗卸载也不丢刷新）
        await refreshFromServer({ force: true })
        if (generation !== userStateGeneration) return { success: false, unknown: true }
        return { ...res, restored }
      }
      // 服务端明确失败也可能已部分提交：同样强制对账
      await refreshFromServer({ force: true })
      if (generation !== userStateGeneration) return { success: false, unknown: true }
      return res
    } catch (e) {
      // 响应丢失时服务端可能已提交恢复：先强制对账，再返回“结果未知”供界面区别提示
      await refreshFromServer({ force: true })
      return { success: false, unknown: true }
    }
  }

  /**
   * 回收站弹窗事件后的侧边栏同步：恢复流程已在 store 内完成强制对账并携带 handled，
   * 这里只处理无载荷的彻底删除（服务端会推进同步序列号，普通版本号刷新即可）。
   * @param {{handled?: boolean}} [payload] 回收站弹窗事件载荷
   * @returns {Promise<boolean>} 刷新结果（当前会话正文是否被合并更新）
   */
  function syncSidebarAfterTrashChange(payload) {
    if (payload && payload.handled) return Promise.resolve(false)
    return refreshFromServer()
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
    // 记录删除时本地已知的行版本：服务端据此识别恢复前产生的陈旧删除声明
    const deletedVersion = chatVersions.value[id] || 0
    delete chats.value[id]
    delete chatSummaries.value[id]
    delete chatMeta.value[id]
    delete chatVersions.value[id]
    dirtyChatIds.delete(id)
    dirtyMetaIds.delete(id)
    pendingRestoreIds.delete(id)
    recordDeletedChat(id, deletedVersion)
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
      // 先记录删除时本地已知的行版本，再清理会话状态
      const deletedVersion = chatVersions.value[id] || 0
      delete chats.value[id]
      delete chatSummaries.value[id]
      delete chatMeta.value[id]
      delete chatVersions.value[id]
      dirtyChatIds.delete(id)
      dirtyMetaIds.delete(id)
      pendingRestoreIds.delete(id)
      recordDeletedChat(id, deletedVersion)
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
    ids.forEach(id => {
      // 清空会话前记录各自已知的行版本，供服务端识别陈旧删除声明
      recordDeletedChat(id, chatVersions.value[id] || 0)
    })
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

  /** 追加消息；补发缺失回答时允许插入指定位置，保留后续对话。 */
  function addMessage(chatId, msg, insertIndex) {
    if (!chats.value[chatId]) chats.value[chatId] = []
    // 为消息分配稳定标识：重试/多端合并时按 id 去重，防止重复消息
    if (!msg.id) msg.id = genMsgId()
    if (Number.isInteger(insertIndex)) chats.value[chatId].splice(insertIndex, 0, msg)
    else chats.value[chatId].push(msg)
    markChatDirty(chatId)
    syncToServer()
  }

  /** 原位更新用户消息正文，保留消息标识及附件并加入同步队列。 */
  function editUserMessage(chatId, msgIdx, content) {
    const message = chats.value[chatId]?.[msgIdx]
    if (!message || message.role !== 'user' || !content?.trim()) return false
    message.content = content.trim()
    // 原输入 Token 属于旧问题，在新回答返回前不可继续展示为新问题的统计。
    delete message.promptTokens
    markChatDirty(chatId)
    syncToServer()
    return true
  }

  // 显式截断会话消息：删除 fromIdx（含）之后的所有消息，不用于编辑重发或回答版本生成。
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

  // 导出全部会话前先补齐懒加载正文，再委托归档模块生成文件。
  async function exportChats(format = 'txt') {
    await ensureAllChatsLoaded()
    downloadAllChats(format, {
      chats: chats.value,
      chatMeta: chatMeta.value,
      folders: folders.value,
      resolveTitle: chatTitle
    })
  }

  // 保留原公开入口，直接导出当前内存中的完整 JSON 备份。
  function exportChatsJson() {
    downloadChatsJson({ chats: chats.value, chatMeta: chatMeta.value, folders: folders.value })
  }

  // 导入计划由纯模块计算，Store 只负责写入状态、标脏和触发同步。
  function importChatsJson(data) {
    const plan = prepareChatImport(data, {
      chats: chats.value,
      chatSummaries: chatSummaries.value,
      folders: folders.value,
      normalizeFolders
    })
    plan.chatEntries.forEach(({ id, messages, meta }) => {
      chats.value[id] = messages
      if (meta) chatMeta.value[id] = { ...meta }
      const deletedIndex = deletedChatIds.value.indexOf(id)
      if (deletedIndex >= 0) deletedChatIds.value.splice(deletedIndex, 1)
      delete deletedChatIdVersions.value[id]
      pendingRestoreIds.add(id)
      markChatDirty(id)
    })
    if (plan.foldersToAdd.length) {
      folders.value.push(...plan.foldersToAdd)
      foldersDirty = true
    }
    if (plan.imported > 0 || plan.foldersToAdd.length > 0) syncToServer()
    return { imported: plan.imported, skipped: plan.skipped }
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
  // Store 保留同步副作用，版本结构与字段映射由纯业务模块维护。

  /**
   * 为 assistant 消息追加回答版本，并将变更加入同步队列。
   */
  function addMessageVersion(chatId, msgIdx, versionData, makeCurrent) {
    const message = chats.value[chatId]?.[msgIdx]
    if (!message || message.role !== 'assistant') return null
    const versionId = appendMessageVersion(message, versionData, makeCurrent)
    if (!versionId) return null
    markChatDirty(chatId)
    syncToServer()
    return versionId
  }

  /**
   * 切换 assistant 消息当前展示版本，并将选择结果同步到服务端。
   */
  function selectMessageVersion(chatId, msgIdx, versionId) {
    const message = chats.value[chatId]?.[msgIdx]
    if (!message || !selectVersion(message, versionId)) return false
    markChatDirty(chatId)
    syncToServer()
    return true
  }

  /**
   * 获取消息版本切换器使用的轻量视图。
   */
  function getMessageVersions(chatId, msgIdx) {
    const message = chats.value[chatId]?.[msgIdx]
    return message ? getMessageVersionView(message) : null
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
    downloadSingleChatMarkdown(chats.value[id] || [], chatTitle(id))
  }
  return {
    chats, chatSummaries, chatMeta, currentChatId, deletedChatIds, deletedChatIdVersions, isChatHistoryLoaded, searchKeyword,
    folders, chatVersions, foldersVersion, syncConflicts,
    sortedChatList, currentMessages,
    loadFromServer, syncToServer, suspendSync, resumeSync, syncCurrentChatFromServer, refreshFromServer,
    applyTrashRestore, restoreChatsFromTrash, waitForSyncIdle, syncSidebarAfterTrashChange,
    ensureChatLoaded, ensureAllChatsLoaded, resolveSyncConflict, genMsgId,
    addMessageVersion, selectMessageVersion, getMessageVersions,
    newChat, switchChat, switchChatLazy, deleteChat, deleteChats, deleteAllChats, createBranch,
    addMessage, editUserMessage, truncateMessages, updateLastAssistantMessage, exportChats, exportChatsJson, importChatsJson, countValidChats, findEmptyChatId,
    togglePin, renameChat, setAutoTitleIfEmpty, exportChatMarkdown, setChatPromptPreset, chatTitle,
    createFolder, renameFolder, deleteFolder, toggleFolderCollapsed, moveChatToFolder, moveChatsToFolder
  }
})
