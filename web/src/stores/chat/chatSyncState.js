const RECOVERY_TTL_MS = 7 * 86400000

/**
 * 生成当前账号隔离的本地恢复快照键。
 */
export function createRecoveryKey(storage = localStorage) {
  let username = 'anonymous'
  try { username = storage.getItem('username') || 'anonymous' } catch { /* 无存储权限时使用匿名空间 */ }
  return `chatai-chat-recovery:${username}`
}

/**
 * 根据脏标记构建增量同步载荷，并附带乐观锁基准版本。
 */
export function createSyncPayload(state) {
  const chatsPayload = {}
  const metaPayload = {}
  state.dirtyChatIds.forEach((revision, id) => {
    if (state.chats[id] !== undefined) chatsPayload[id] = state.chats[id]
    if (state.chatMeta[id]) metaPayload[id] = state.chatMeta[id]
  })
  state.dirtyMetaIds.forEach((revision, id) => {
    if (!state.dirtyChatIds.has(id) && state.chatMeta[id]) metaPayload[id] = state.chatMeta[id]
  })
  const baseVersions = {}
  Object.keys(chatsPayload).forEach(id => { baseVersions[id] = state.chatVersions[id] || 0 })
  Object.keys(metaPayload).forEach(id => {
    if (baseVersions[id] === undefined) baseVersions[id] = state.chatVersions[id] || 0
  })
  return {
    lastChatId: state.currentChatId,
    chats: chatsPayload,
    chatMeta: metaPayload,
    deletedChatIds: [...state.deletedChatIds],
    folders: state.foldersDirty ? state.folders : undefined,
    baseVersions,
    baseFoldersVersion: state.foldersVersion,
    restoreChatIds: [...state.pendingRestoreIds],
    baseVersion: state.remoteVersion
  }
}

/**
 * 将待同步载荷持久化为可恢复草稿；浏览器禁用存储时静默降级。
 */
export function persistSyncRecovery(storage, key, revision, payload) {
  try {
    storage.setItem(key, JSON.stringify({ revision, savedAt: Date.now(), payload }))
  } catch { /* 存储空间不足时仍继续服务端同步 */ }
}

/**
 * 读取仍在有效期内的同步恢复草稿，并主动清理损坏或过期数据。
 */
export function readSyncRecovery(storage, key, now = Date.now()) {
  try {
    const raw = storage.getItem(key)
    if (!raw) return null
    const draft = JSON.parse(raw)
    if (!draft?.payload || now - Number(draft.savedAt || 0) > RECOVERY_TTL_MS) {
      storage.removeItem(key)
      return null
    }
    return draft
  } catch {
    try { storage.removeItem(key) } catch { /* 无存储权限时忽略清理失败 */ }
    return null
  }
}
