// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import { saveChatHistory, loadSingleChatHistory, loadChatSummaries, restoreTrash } from '@/api/chat'

vi.mock('@/api/chat', () => ({
  loadChatHistory: vi.fn(),
  loadChatSummaries: vi.fn(),
  loadChatVersion: vi.fn(),
  loadSingleChatHistory: vi.fn(),
  saveChatHistory: vi.fn(async () => ({ success: true, version: 1 })),
  restoreTrash: vi.fn(async chatIds => ({ success: true, restored: chatIds }))
}))

describe('会话分支与批量操作', () => {
  let store

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    store = useChatStore()
    store.isChatHistoryLoaded = true
  })

  afterEach(() => {
    store.$dispose()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('创建分支时保留原会话并仅复制指定位置之前的消息', () => {
    const original = [
      { role: 'user', content: '问题一' },
      { role: 'assistant', content: '回答一' },
      { role: 'user', content: '问题二' }
    ]
    store.chats = { source: original }
    store.chatMeta = { source: { title: '原会话' } }

    const branchId = store.createBranch('source', 1)

    expect(branchId).toMatch(/^branch_/)
    expect(store.chats.source).toEqual(original)
    expect(store.chats[branchId]).toEqual(original.slice(0, 2))
    expect(store.chatMeta[branchId]).toMatchObject({ parentChatId: 'source', branchFromIndex: 1 })
    expect(store.currentChatId).toBe(branchId)
  })

  it('批量移动与删除会话时去重并保留未选中的会话', () => {
    store.chats = {
      a: [{ role: 'user', content: 'A' }],
      b: [{ role: 'user', content: 'B' }],
      c: [{ role: 'user', content: 'C' }]
    }
    store.currentChatId = 'c'
    const folderId = store.createFolder('待整理')

    store.moveChatsToFolder(['a', 'a', 'b'], folderId)
    expect(store.chatMeta.a.folderId).toBe(folderId)
    expect(store.chatMeta.b.folderId).toBe(folderId)

    store.deleteChats(['a', 'a', 'b'])
    expect(store.chats.a).toBeUndefined()
    expect(store.chats.b).toBeUndefined()
    expect(store.chats.c).toBeDefined()
    expect(store.deletedChatIds).toEqual(['a', 'b'])
  })
})

describe('多端同步：增量载荷、版本基准与冲突处理', () => {
  let store

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    store = useChatStore()
    store.isChatHistoryLoaded = true
    vi.resetAllMocks()
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 2, versions: {}, foldersVersion: 1, conflicts: [] }))
    restoreTrash.mockResolvedValue({ success: true, restored: [] })
  })

  afterEach(() => {
    store.$dispose()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  /** 触发防抖同步并等待完成 */
  async function flushSync() {
    await vi.advanceTimersByTimeAsync(600)
  }

  it('禁用本地存储时仍恢复服务端会话并正常保存新增消息', async () => {
    const storageGetter = vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('Storage disabled', 'SecurityError')
    })
    try {
      loadChatSummaries.mockResolvedValue({
        success: true, lastChatId: 'a', summaries: [{ id: 'a', count: 1, version: 5 }]
      })
      loadSingleChatHistory.mockResolvedValue({
        success: true, messages: [{ role: 'user', content: '服务端消息' }], version: 5
      })
      await store.loadFromServer()
      expect(store.currentChatId).toBe('a')
      expect(store.chats.a).toHaveLength(1)
      store.addMessage('a', { role: 'assistant', content: '新增回答' })
      await flushSync()
      expect(saveChatHistory).toHaveBeenCalledTimes(1)
      expect(saveChatHistory.mock.calls[0][0].chats.a).toHaveLength(2)
    } finally {
      storageGetter.mockRestore()
    }
  })

  it('同步载荷仅包含变更会话，未变更的已加载会话不上传', async () => {
    // 模拟首屏加载后的状态：a/b 两个已加载会话，各有服务端版本
    store.chats = { a: [{ role: 'user', content: 'A' }], b: [{ role: 'user', content: 'B' }] }
    store.chatVersions = { a: 5, b: 7 }
    store.currentChatId = 'a'

    // 仅修改会话 a
    store.addMessage('a', { role: 'assistant', content: 'A回答' })
    await flushSync()

    expect(saveChatHistory).toHaveBeenCalledTimes(1)
    const payload = saveChatHistory.mock.calls[0][0]
    expect(Object.keys(payload.chats)).toEqual(['a'])
    expect(payload.baseVersions).toEqual({ a: 5 })
    expect(payload.chats.a).toHaveLength(2)
  })

  it('旧草稿恢复保留原始基准版本，较短消息和元信息也不能冒用服务端最新版本', async () => {
    localStorage.setItem('username', 'draft-user')
    localStorage.setItem('chatai-chat-recovery:draft-user', JSON.stringify({
      revision: 4, savedAt: Date.now(), payload: {
        chats: { a: [{ role: 'user', content: '版本6的离线内容' }] },
        chatMeta: { a: { title: '旧标题' }, b: { title: '离线重命名' } },
        baseVersions: { a: 6, b: 3 }, folders: [{ id: 'local', name: '离线文件夹' }],
        baseFoldersVersion: 1, lastChatId: 'a'
      }
    }))
    loadChatSummaries.mockResolvedValue({
      success: true, summaries: [{ id: 'a', count: 2, version: 8 }, { id: 'b', count: 1, version: 5 }],
      foldersVersion: 4, version: 20, lastChatId: 'a'
    })
    loadSingleChatHistory.mockResolvedValue({
      success: true, messages: [{ role: 'user', content: '版本8的远端内容' }], version: 8, exists: true
    })
    saveChatHistory.mockResolvedValue({
      success: true, version: 21, versions: {}, foldersVersion: 4,
      conflicts: [{ chatId: 'a', reason: 'version', serverVersion: 8 }]
    })
    await store.loadFromServer()
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0]).toMatchObject({
      baseVersions: { a: 6, b: 3 }, baseFoldersVersion: 1,
      chats: { a: [{ content: '版本6的离线内容' }] }
    })
    expect(store.chats.a[0].content).toBe('版本8的远端内容')
    expect(store.syncConflicts[0].localMessages[0].content).toBe('版本6的离线内容')
    const recovery = JSON.parse(localStorage.getItem('chatai-chat-recovery:draft-user'))
    expect(recovery.payload.recoveryConflicts[0].localMessages[0].content).toBe('版本6的离线内容')
    store.$dispose()
    setActivePinia(createPinia())
    store = useChatStore()
    await store.loadFromServer()
    expect(store.syncConflicts[0].localMessages[0].content).toBe('版本6的离线内容')
  })

  it('无基准版本的旧格式草稿保守使用0，不覆盖已有远端会话', async () => {
    localStorage.setItem('chatai-chat-recovery:anonymous', JSON.stringify({
      revision: 1, savedAt: Date.now(), payload: { chats: { a: [] }, lastChatId: 'a' }
    }))
    loadChatSummaries.mockResolvedValue({ success: true, summaries: [{ id: 'a', count: 2, version: 9 }] })
    await store.loadFromServer()
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0].baseVersions.a).toBe(0)
    expect(saveChatHistory.mock.calls[0][0].chats.a).toEqual([])
  })

  it('当前会话只有重命名草稿时，懒加载保留旧基准，解决冲突不会上传空正文', async () => {
    localStorage.setItem('chatai-chat-recovery:anonymous', JSON.stringify({
      revision: 2, savedAt: Date.now(), payload: {
        chats: {}, chatMeta: { a: { title: '离线标题' } }, baseVersions: { a: 6 }, lastChatId: 'a'
      }
    }))
    loadChatSummaries.mockResolvedValue({ success: true, lastChatId: 'a',
      chatMeta: { a: { title: '远端标题' } }, summaries: [{ id: 'a', count: 2, version: 8 }] })
    const messages = [{ role: 'user', content: '原问题' }, { role: 'assistant', content: '原回答' }]
    loadSingleChatHistory.mockResolvedValue({ success: true, exists: true, version: 8,
      messages, meta: { title: '远端标题' } })
    saveChatHistory.mockResolvedValue({ success: true, version: 9, versions: {}, foldersVersion: 0,
      conflicts: [{ chatId: 'a', reason: 'version', serverVersion: 8 }] })
    await store.loadFromServer()
    expect(store.chatMeta.a.title).toBe('离线标题')
    expect(store.chatVersions.a).toBe(6)
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0]).toMatchObject({ chats: {}, baseVersions: { a: 6 }, chatMeta: { a: { title: '离线标题' } } })
    expect(store.syncConflicts[0].hasLocalMessages).toBe(false)
    saveChatHistory.mockClear()
    saveChatHistory.mockResolvedValue({ success: true, version: 10, versions: { a: 9 }, foldersVersion: 0, conflicts: [] })
    store.resolveSyncConflict('a', true)
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0]).toMatchObject({ chats: {}, baseVersions: { a: 8 }, chatMeta: { a: { title: '离线标题' } } })
    expect(store.chats.a).toEqual(messages)
  })

  it('纯元信息的删除冲突恢复后立即补回正文和会话列表', async () => {
    store.chatMeta = { b: { title: '离线标题' } }
    store.chatSummaries = { b: { id: 'b', title: '原标题', count: 2, version: 6 } }
    store.chatVersions = { b: 6 }
    store.renameChat('b', '离线标题')
    loadSingleChatHistory.mockResolvedValue({ success: true, exists: false, messages: [], version: 0 })
    saveChatHistory.mockResolvedValue({ success: true, version: 8, versions: {}, foldersVersion: 0,
      conflicts: [{ chatId: 'b', reason: 'deleted', serverVersion: 0 }] })
    await flushSync()
    expect(store.syncConflicts[0].hasLocalMessages).toBe(false)
    const messages = [{ role: 'user', content: '原问题' }, { role: 'assistant', content: '原回答' }]
    loadSingleChatHistory.mockResolvedValue({ success: true, exists: true, messages,
      meta: { title: '离线标题' }, version: 9 })
    saveChatHistory.mockResolvedValue({ success: true, version: 9, versions: { b: 9 }, foldersVersion: 0, conflicts: [] })
    saveChatHistory.mockClear()
    store.resolveSyncConflict('b', true)
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0].chats).toEqual({})
    expect(saveChatHistory.mock.calls[0][0].restoreChatIds).toContain('b')
    expect(store.chats.b).toEqual(messages)
    expect(store.sortedChatList.total).toBe(1)
  })

  it('恢复补拉期间再次删除会话，迟到的正文不会把已删除会话放回列表', async () => {
    store.chats = { b: [{ role: 'user', content: '原问题' }] }
    store.chatMeta = { b: { title: '原名' } }
    store.syncConflicts = [{ chatId: 'b', reason: 'deleted', hasLocalMessages: false,
      localMessages: null, localMeta: { title: '离线标题' }, serverVersion: 0 }]
    let resolveLoad
    loadSingleChatHistory.mockImplementation(() => new Promise(resolve => { resolveLoad = resolve }))
    saveChatHistory.mockResolvedValue({ success: true, version: 9, versions: { b: 9 }, foldersVersion: 0, conflicts: [] })
    store.resolveSyncConflict('b', true)
    await vi.advanceTimersByTimeAsync(500)
    expect(resolveLoad).toBeTypeOf('function')
    store.deleteChat('b')
    resolveLoad({ success: true, exists: true, messages: [{ role: 'user', content: '原问题' }],
      meta: { title: '离线标题' }, version: 9 })
    await flushSync()
    expect(store.deletedChatIds).toContain('b')
    expect(store.chats.b).toBeUndefined()
    expect(store.sortedChatList.total).toBe(0)
  })

  it('元信息变更（置顶未加载会话）仅上传元信息且携带基准版本', async () => {
    store.chatMeta = { x: { pinned: false } }
    store.chatVersions = { x: 3 }
    store.togglePin('x')
    await flushSync()

    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.chats).toEqual({})
    expect(payload.chatMeta.x.pinned).toBe(true)
    expect(payload.baseVersions).toEqual({ x: 3 })
  })

  it('保存成功后按响应更新会话版本号并清除脏标记，后续无变更不再上传', async () => {
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 9, versions: { a: 6 }, foldersVersion: 0, conflicts: [] }))
    store.chats = { a: [{ role: 'user', content: 'A' }] }
    store.chatVersions = { a: 5 }
    store.currentChatId = 'a'
    store.addMessage('a', { role: 'assistant', content: 'R' })
    await flushSync()

    expect(store.chatVersions.a).toBe(6)
    saveChatHistory.mockClear()
    // 无新变更时仅切换会话（lastChatId 同步）不应携带会话正文
    store.switchChat('a')
    await flushSync()
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.chats).toEqual({})
  })

  it('版本冲突：服务端数据入主视图，本地副本保留在冲突记录，选择保留本地后以新版本重传', async () => {
    loadSingleChatHistory.mockResolvedValue({
      success: true,
      messages: [{ role: 'user', content: '服务端版本' }],
      meta: { title: '服务端标题' },
      version: 8,
      exists: true
    })
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 10, versions: {}, foldersVersion: 0,
      conflicts: [{ chatId: 'a', reason: 'version', serverVersion: 8 }]
    }))
    store.chats = { a: [{ role: 'user', content: '本地版本' }] }
    store.chatVersions = { a: 5 }
    store.currentChatId = 'a'

    store.addMessage('a', { role: 'assistant', content: '本地新增' })
    await flushSync()
    // 冲突拉取为异步，推进微任务
    await vi.advanceTimersByTimeAsync(0)

    expect(store.syncConflicts).toHaveLength(1)
    expect(store.syncConflicts[0].chatId).toBe('a')
    expect(store.syncConflicts[0].localMessages).toHaveLength(2)
    expect(store.chats.a).toEqual([{ role: 'user', content: '服务端版本' }])
    expect(store.chatVersions.a).toBe(8)

    // 选择保留本地：以服务端版本 8 为基准重传本地副本
    saveChatHistory.mockClear()
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 11, versions: { a: 9 }, foldersVersion: 0, conflicts: [] }))
    store.resolveSyncConflict('a', true)
    await flushSync()
    expect(store.syncConflicts).toHaveLength(0)
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.baseVersions.a).toBe(8)
    expect(payload.chats.a).toHaveLength(2)
    expect(store.chatVersions.a).toBe(9)
  })

  it('删除冲突：选择保留本地时声明显式恢复', async () => {
    loadSingleChatHistory.mockResolvedValue({ success: true, messages: [], meta: null, version: 0, exists: false })
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 10, versions: {}, foldersVersion: 0,
      conflicts: [{ chatId: 'a', reason: 'deleted', serverVersion: 0 }]
    }))
    store.chats = { a: [{ role: 'user', content: '本地内容' }] }
    store.chatVersions = { a: 5 }
    store.currentChatId = 'a'

    store.addMessage('a', { role: 'assistant', content: '新增' })
    await flushSync()
    await vi.advanceTimersByTimeAsync(0)
    expect(store.syncConflicts[0].reason).toBe('deleted')

    saveChatHistory.mockClear()
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 11, versions: { a: 1 }, foldersVersion: 0, conflicts: [] }))
    store.resolveSyncConflict('a', true)
    await flushSync()
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.restoreChatIds).toContain('a')
  })

  it('消息稳定标识：发送前合并按 id 去重，防止重试产生重复消息', async () => {
    store.chats = { a: [{ id: 'm1', role: 'user', content: '问题' }, { id: 'm2', role: 'assistant', content: '本地回答' }] }
    store.chatVersions = { a: 5 }
    store.currentChatId = 'a'
    // 服务端已包含 m2（之前的同步其实成功了），同时返回更高版本
    loadSingleChatHistory.mockResolvedValue({
      success: true,
      messages: [{ id: 'm1', role: 'user', content: '问题' }, { id: 'm2', role: 'assistant', content: '本地回答' }],
      meta: null,
      version: 6,
      exists: true
    })
    const merged = await store.syncCurrentChatFromServer('a', 1)
    expect(merged).toBe(true)
    expect(store.chats.a).toHaveLength(2)
    expect(store.chats.a.map(m => m.id)).toEqual(['m1', 'm2'])
  })

  it('applyServerState 以版本号识别同条数变更并踢回未加载态', async () => {
    store.chats = { a: [{ role: 'user', content: 'A' }], b: [{ role: 'user', content: 'B' }] }
    store.chatVersions = { a: 5, b: 7 }
    store.currentChatId = 'b'
    store.refreshFromServer // 引用防 tree-shake 误删
    // 直接调用内部状态合并：通过 refreshFromServer 触发（mock 版本变化）
    const { loadChatVersion, loadChatSummaries } = await import('@/api/chat')
    loadChatVersion.mockResolvedValue({ success: true, version: 99 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 99,
      foldersVersion: 0,
      chatMeta: { a: {}, b: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 6 }, // 同条数但版本变化
        { id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 7 }  // 版本一致
      ]
    })
    await store.refreshFromServer()
    // a 版本变化被踢回未加载态（切换时按需重拉），b 版本一致保留在内存
    expect(store.chats.a).toBeUndefined()
    expect(store.chats.b).toBeDefined()
    expect(store.chatVersions.a).toBe(6)
  })

  it('回收站恢复后刷新摘要：本地陈旧删除列表不再隐藏已恢复会话', async () => {
    // 本地状态：会话 a 此前被删除，本地删除列表仍保留其ID（回收站恢复接口不经过 store）
    store.deletedChatIds = ['a']
    store.chats = {}
    store.chatSummaries = {}
    const { loadChatVersion } = await import('@/api/chat')
    loadChatVersion.mockResolvedValue({ success: true, version: 42 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 42,
      foldersVersion: 0,
      chatMeta: { a: { title: '已恢复会话' } },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: '已恢复会话', preview: '问题', lastTime: null, count: 1, version: 3 }]
    })
    await store.refreshFromServer()
    // 恢复的会话应出现在侧边栏摘要中，且本地删除态被服务端结果纠正
    expect(store.chatSummaries.a).toMatchObject({ title: '已恢复会话' })
    expect(store.deletedChatIds).toEqual([])
  })

  it('回收站恢复成功后立即解除本地删除态，防止后续同步重新软删除', () => {
    store.deletedChatIds = ['a', 'b']
    store.applyTrashRestore(['a'])
    expect(store.deletedChatIds).toEqual(['b'])
  })

  it('恢复后本地保存推进版本号时，普通刷新跳过、强制刷新仍能补进侧边栏', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    loadChatVersion.mockResolvedValue({ success: true, version: 5 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 5,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: '会话A', preview: 'A', lastTime: null, count: 1, version: 1 }]
    })
    await store.refreshFromServer()
    expect(store.chatSummaries.a).toBeDefined()
    // 恢复接口把服务端序列号推进到 6；随后本地保存也拿到 6，普通刷新因版本相等而跳过
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 6, versions: {}, foldersVersion: 0, conflicts: []
    }))
    store.switchChat('a')
    await flushSync()
    loadChatVersion.mockResolvedValue({ success: true, version: 6 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 6,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: '会话A', preview: 'A', lastTime: null, count: 1, version: 1 },
        { id: 'b', title: '已恢复B', preview: 'B', lastTime: null, count: 1, version: 1 }
      ]
    })
    await store.refreshFromServer()
    expect(store.chatSummaries.b).toBeUndefined()
    await store.refreshFromServer({ force: true })
    expect(store.chatSummaries.b).toMatchObject({ title: '已恢复B' })
  })

  it('强制刷新遇同步繁忙时挂起，保存完成后自动补拉摘要', async () => {
    store.chats = { a: [{ role: 'user', content: 'A' }] }
    store.chatMeta = { a: {} }
    store.chatVersions = { a: 1 }
    store.currentChatId = 'a'
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 5, versions: { a: 2 }, foldersVersion: 0, conflicts: []
    }))
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 5,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 2 }]
    })
    store.switchChat('a')
    expect(await store.refreshFromServer({ force: true })).toBe(false)
    expect(loadChatSummaries).not.toHaveBeenCalled()
    await flushSync()
    await vi.advanceTimersByTimeAsync(0)
    // 保存结束后 finally 自动补执行强制刷新
    expect(loadChatSummaries).toHaveBeenCalledTimes(1)
    expect(store.chatVersions.a).toBe(2)
  })

  it('同步挂起期间的强制刷新在恢复同步后自动补拉', async () => {
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 7,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'b', title: '已恢复B', preview: 'B', lastTime: null, count: 1, version: 1 }]
    })
    store.suspendSync()
    expect(await store.refreshFromServer({ force: true })).toBe(false)
    expect(loadChatSummaries).not.toHaveBeenCalled()
    store.resumeSync()
    await vi.advanceTimersByTimeAsync(0)
    expect(loadChatSummaries).toHaveBeenCalledTimes(1)
    expect(store.chatSummaries.b).toBeDefined()
  })

  it('强制刷新请求失败保留待执行标记，下一次刷新重试成功', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    loadChatSummaries
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({
        success: true,
        version: 8,
        foldersVersion: 0,
        chatMeta: {},
        deletedChatIds: [],
        folders: [],
        summaries: [{ id: 'b', title: '已恢复B', preview: 'B', lastTime: null, count: 1, version: 1 }]
      })
    expect(await store.refreshFromServer({ force: true })).toBe(false)
    // 请求失败不在 finally 立即热循环重试
    await vi.advanceTimersByTimeAsync(0)
    expect(loadChatSummaries).toHaveBeenCalledTimes(1)
    // 下一次自动轮询会被待执行标记提升为强制刷新并成功应用
    await store.refreshFromServer()
    expect(loadChatSummaries).toHaveBeenCalledTimes(2)
    expect(store.chatSummaries.b).toBeDefined()
    expect(loadChatVersion).not.toHaveBeenCalled()
  })

  it('摘要请求期间本地删除完成后丢弃旧快照，不复活已删会话', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.chats = {
      a: [{ role: 'user', content: 'A' }],
      cur: [{ role: 'user', content: 'C' }]
    }
    store.chatMeta = { a: {}, cur: {} }
    store.chatVersions = { a: 1, cur: 1 }
    store.deletedChatIds = []
    store.currentChatId = 'cur'
    loadChatVersion.mockResolvedValue({ success: true, version: 50 })
    let resolveSummaries
    loadChatSummaries
      .mockImplementationOnce(() => new Promise(resolve => { resolveSummaries = resolve }))
      // 过期快照被丢弃后会自动补拉一次，返回删除后的真实服务端状态
      .mockResolvedValueOnce({
        success: true,
        version: 60,
        foldersVersion: 0,
        chatMeta: { cur: {} },
        deletedChatIds: ['a'],
        folders: [],
        summaries: [{ id: 'cur', title: 'C', preview: 'C', lastTime: null, count: 1, version: 1 }]
      })
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 60, versions: {}, foldersVersion: 0, conflicts: []
    }))
    const pending = store.refreshFromServer()
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveSummaries).toBeTypeOf('function')
    // 摘要请求在途时删除会话 a，并完成本地保存（服务端序列号推进到 60）
    store.deleteChat('a')
    await flushSync()
    // 返回删除前生成的旧快照（仍包含 a 且删除列表不含 a）
    resolveSummaries({
      success: true,
      version: 50,
      foldersVersion: 0,
      chatMeta: { a: {}, cur: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 2 },
        { id: 'cur', title: 'C', preview: 'C', lastTime: null, count: 1, version: 1 }
      ]
    })
    await pending
    await vi.advanceTimersByTimeAsync(0)
    // 旧快照被丢弃：本地删除态与会话摘要都不被覆盖
    expect(store.deletedChatIds).toContain('a')
    expect(store.chatSummaries.a).toBeUndefined()
  })

  it('恢复只从本地恢复草稿删除已恢复ID，保留其他未同步内容', async () => {
    store.deletedChatIds = ['a', 'b']
    // 写入一份恢复前落盘的草稿：包含另一会话未上传的正文/元信息/基准版本
    localStorage.setItem('chatai-chat-recovery:anonymous', JSON.stringify({
      revision: 7,
      savedAt: Date.now(),
      payload: {
        chats: { x: [{ role: 'user', content: '离线内容' }] },
        chatMeta: { x: { title: '离线标题' } },
        deletedChatIds: ['a', 'b'],
        deletedChatIdVersions: { a: 4, b: 5 },
        restoreChatIds: ['x'],
        baseVersions: { x: 3 },
        baseFoldersVersion: 2,
        lastChatId: 'x'
      }
    }))
    store.applyTrashRestore(['a'])
    const draft = JSON.parse(localStorage.getItem('chatai-chat-recovery:anonymous'))
    // 仅删除列表被更新，草稿 revision 与其他未上传内容原样保留
    expect(draft.revision).toBe(7)
    expect(draft.payload.deletedChatIds).toEqual(['b'])
    expect(draft.payload.deletedChatIdVersions).toEqual({ b: 5 })
    expect(draft.payload.chats.x).toEqual([{ role: 'user', content: '离线内容' }])
    expect(draft.payload.chatMeta.x).toEqual({ title: '离线标题' })
    expect(draft.payload.restoreChatIds).toEqual(['x'])
    expect(draft.payload.baseVersions).toEqual({ x: 3 })
    expect(draft.payload.baseFoldersVersion).toBe(2)
    expect(draft.payload.lastChatId).toBe('x')
    // 后续同步载荷也不再声明删除已恢复会话
    store.switchChat('x')
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0].deletedChatIds).toEqual(['b'])
  })

  it('首屏摘要失败时先合并恢复草稿，不丢失未同步内容', async () => {
    store.isChatHistoryLoaded = false
    localStorage.setItem('chatai-chat-recovery:anonymous', JSON.stringify({
      revision: 5,
      savedAt: Date.now(),
      payload: {
        chats: { x: [{ role: 'user', content: '离线问题' }] },
        chatMeta: { x: { title: '离线标题' } },
        deletedChatIds: ['d'],
        deletedChatIdVersions: { d: 3 },
        restoreChatIds: [],
        baseVersions: { x: 2 },
        baseFoldersVersion: 0,
        lastChatId: 'x'
      }
    }))
    loadChatSummaries.mockResolvedValue({ success: false })
    await store.loadFromServer()
    expect(store.chats.x).toEqual([{ role: 'user', content: '离线问题' }])
    expect(store.chatMeta.x.title).toBe('离线标题')
    expect(store.deletedChatIds).toContain('d')
    expect(store.deletedChatIdVersions.d).toBe(3)
    expect(store.isChatHistoryLoaded).toBe(true)
  })

  it('切换账号后重新加载会清空上一账号的会话状态与冲突', async () => {
    localStorage.setItem('username', 'userA')
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 1,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 1 }]
    })
    await store.loadFromServer()
    // 模拟账号 A 的正文、当前会话与未决冲突
    store.chats = { a: [{ role: 'user', content: '账号A私密内容' }] }
    store.currentChatId = 'a'
    store.syncConflicts = [{ chatId: 'a', reason: 'deleted', localMessages: [] }]
    // 换成账号 B，且摘要加载失败
    localStorage.setItem('username', 'userB')
    loadChatSummaries.mockReset()
    loadChatSummaries.mockRejectedValue(new Error('network'))
    await store.loadFromServer()
    expect(store.chats.a).toBeUndefined()
    expect(store.chatMeta.a).toBeUndefined()
    expect(store.syncConflicts).toEqual([])
  })

  it('批量删除/清空会为已记录删除项补写当前行版本', async () => {
    store.deletedChatIds = ['a']
    store.deletedChatIdVersions = { a: 0 }
    store.chats = {
      a: [{ role: 'user', content: 'A' }],
      b: [{ role: 'user', content: 'B' }]
    }
    store.chatSummaries = {
      a: { id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 4 },
      b: { id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 2 }
    }
    store.chatVersions = { a: 4, b: 2 }
    store.currentChatId = 'other'
    store.deleteChats(['a', 'b'])
    await flushSync()
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.deletedChatIdVersions.a).toBe(4)
    expect(payload.deletedChatIdVersions.b).toBe(2)
  })

  it('回收站恢复成功后清理同一会话的未决同步冲突', async () => {
    store.deletedChatIds = ['a']
    store.syncConflicts = [
      { chatId: 'a', reason: 'deleted', localMessages: [] },
      { chatId: 'b', reason: 'version' }
    ]
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'], versions: { a: 2 } })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 5,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 2 }]
    })
    await store.restoreChatsFromTrash(['a'])
    expect(store.syncConflicts.map(entry => entry.chatId)).toEqual(['b'])
  })

  it('服务端文件夹版本回退到0时以服务端为准', async () => {
    store.foldersVersion = 5
    store.deletedChatIds = []
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 10,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: []
    })
    await store.refreshFromServer({ force: true })
    expect(store.foldersVersion).toBe(0)
  })

  it('删除会话时记录删除时已知行版本，供服务端识别陈旧删除声明', async () => {
    store.chats = { a: [{ role: 'user', content: 'A' }] }
    store.chatVersions = { a: 7 }
    store.currentChatId = 'other'
    store.deleteChat('a')
    await flushSync()
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.deletedChatIds).toContain('a')
    expect(payload.deletedChatIdVersions.a).toBe(7)
  })

  it('重复删除同一会话不会把已记录的删除版本降级为0', async () => {
    store.chats = { a: [{ role: 'user', content: 'A' }] }
    store.chatVersions = { a: 7 }
    store.currentChatId = 'other'
    store.deleteChat('a')
    store.deleteChat('a')
    await flushSync()
    const payload = saveChatHistory.mock.calls[0][0]
    expect(payload.deletedChatIds.filter(id => id === 'a')).toHaveLength(1)
    expect(payload.deletedChatIdVersions.a).toBe(7)
  })

  it('恢复响应携带的行版本立即写回本地，刷新前删除不会使用旧基准', async () => {
    store.deletedChatIds = ['a']
    store.chatVersions = { a: 1 }
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'], versions: { a: 5 } })
    await store.restoreChatsFromTrash(['a'])
    expect(store.chatVersions.a).toBe(5)
  })

  it('恢复已加载会话不抬高正文基准，刷新时用服务端恢复内容覆盖旧正文', async () => {
    store.chats = { a: [{ role: 'user', content: '旧正文' }] }
    store.chatVersions = { a: 3 }
    store.currentChatId = 'a'
    store.deletedChatIds = ['a']
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'], versions: { a: 5 } })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 10,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: '服务端', lastTime: null, count: 1, version: 5 }]
    })
    loadSingleChatHistory.mockResolvedValue({
      success: true,
      messages: [{ role: 'user', content: '服务端恢复内容' }],
      meta: {},
      version: 5,
      exists: true
    })
    await store.restoreChatsFromTrash(['a'])
    // 已加载会话保持旧正文基准，强制刷新才能识别服务端变更并合并恢复后的正文
    expect(store.chats.a).toEqual([{ role: 'user', content: '服务端恢复内容' }])
    expect(store.chatVersions.a).toBe(5)
  })

  it('恢复后刷新前删除已加载会话使用恢复返回的行版本', async () => {
    store.chats = { a: [{ role: 'user', content: '旧正文' }] }
    store.chatVersions = { a: 3 }
    store.currentChatId = 'a'
    store.deletedChatIds = ['a']
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'], versions: { a: 5 } })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 10,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: '旧', lastTime: null, count: 1, version: 5 }]
    })
    // 刷新时正文合并失败：chatVersions 保持恢复前旧值
    loadSingleChatHistory.mockResolvedValue({ success: false })
    await store.restoreChatsFromTrash(['a'])
    expect(store.chatVersions.a).toBe(3)
    store.deleteChat('a')
    await flushSync()
    const payload = saveChatHistory.mock.calls.at(-1)[0]
    expect(payload.deletedChatIdVersions.a).toBe(5)
  })

  it('旧服务端缺 deletedChatIds 时保留本地删除列表，不复活已删会话', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.deletedChatIds = ['a']
    store.chats = {}
    store.chatSummaries = {}
    loadChatVersion.mockResolvedValue({ success: true, version: 8 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 8,
      foldersVersion: 0,
      chatMeta: {},
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 1 },
        { id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 1 }
      ]
    })
    await store.refreshFromServer()
    expect(store.chatSummaries.a).toBeUndefined()
    expect(store.chatSummaries.b).toBeDefined()
    expect(store.deletedChatIds).toEqual(['a'])
  })

  it('强制刷新为未加载的恢复会话补齐版本基准，置顶不再误报版本冲突', async () => {
    store.chats = {}
    store.chatSummaries = {}
    store.chatVersions = {}
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 9,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 3 }]
    })
    await store.refreshFromServer({ force: true })
    expect(store.chats.a).toBeUndefined()
    expect(store.chatVersions.a).toBe(3)
    store.togglePin('a')
    await flushSync()
    expect(saveChatHistory.mock.calls[0][0].baseVersions.a).toBe(3)
  })

  it('当前会话版本变化时仍合并其他端正文，不被版本回填提前阻断', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.chats = {
      a: [{ role: 'user', content: '问题' }],
      cur: [{ role: 'user', content: '另一个会话' }]
    }
    store.chatMeta = { a: {}, cur: {} }
    store.chatVersions = { a: 5, cur: 1 }
    store.currentChatId = 'a'
    loadChatVersion.mockResolvedValue({ success: true, version: 99 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 99,
      foldersVersion: 0,
      chatMeta: { a: {}, cur: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: '问题', lastTime: null, count: 2, version: 6 },
        { id: 'cur', title: 'C', preview: '另一个会话', lastTime: null, count: 1, version: 1 }
      ]
    })
    loadSingleChatHistory.mockResolvedValue({
      success: true,
      messages: [{ role: 'user', content: '问题' }, { role: 'assistant', content: '其他端的回答' }],
      meta: {},
      version: 6,
      exists: true
    })
    const merged = await store.refreshFromServer()
    // 当前会话不能先被摘要版本抬高基准，否则版本相等会跳过正文合并并覆盖服务端内容
    expect(merged).toBe(true)
    expect(store.chats.a).toHaveLength(2)
    expect(store.chatVersions.a).toBe(6)
  })

  it('在途普通刷新不消费恢复请求的强制刷新标记，过期快照被丢弃后自动补拉', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.deletedChatIds = ['a']
    loadChatVersion.mockResolvedValue({ success: true, version: 10 })
    let resolveStale
    loadChatSummaries
      .mockImplementationOnce(() => new Promise(resolve => { resolveStale = resolve }))
      .mockResolvedValueOnce({
        success: true,
        version: 11,
        foldersVersion: 0,
        chatMeta: { a: {} },
        deletedChatIds: [],
        folders: [],
        summaries: [{ id: 'a', title: '已恢复A', preview: 'A', lastTime: null, count: 1, version: 2 }]
      })
    const pending = store.refreshFromServer()
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveStale).toBeTypeOf('function')
    // 刷新在途时恢复会话：applyTrashRestore 递增状态代次，强制刷新因 refreshing 被延后
    store.applyTrashRestore(['a'])
    await store.refreshFromServer({ force: true })
    resolveStale({
      success: true,
      version: 10,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: ['a'],
      folders: [],
      summaries: []
    })
    await pending
    await vi.advanceTimersByTimeAsync(0)
    // 旧快照被丢弃，延后的强制刷新补拉成功，恢复的会话出现在侧边栏
    expect(loadChatVersion).toHaveBeenCalledTimes(1)
    expect(loadChatSummaries).toHaveBeenCalledTimes(2)
    expect(store.chatSummaries.a).toMatchObject({ title: '已恢复A' })
    expect(store.deletedChatIds).toEqual([])
  })

  it('回收站恢复等待在途保存完成后再提交，避免旧删除载荷覆盖恢复结果', async () => {
    store.deletedChatIds = ['a']
    store.chats = { cur: [{ role: 'user', content: 'C' }] }
    store.chatVersions = { cur: 1 }
    store.currentChatId = 'cur'
    let resolveSave
    saveChatHistory.mockImplementation(() => new Promise(resolve => { resolveSave = resolve }))
    store.addMessage('cur', { role: 'assistant', content: 'R' })
    await vi.advanceTimersByTimeAsync(500)
    expect(resolveSave).toBeTypeOf('function')
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'] })
    const restoring = store.restoreChatsFromTrash(['a'])
    await vi.advanceTimersByTimeAsync(100)
    // 在途保存结束前不得提交恢复请求（旧载荷仍携带删除声明）
    expect(restoreTrash).not.toHaveBeenCalled()
    expect(store.deletedChatIds).toEqual(['a'])
    resolveSave({ success: true, version: 9, versions: { cur: 2 }, foldersVersion: 0, conflicts: [] })
    await vi.advanceTimersByTimeAsync(100)
    expect(await restoring).toEqual({ success: true, restored: ['a'] })
    expect(restoreTrash).toHaveBeenCalledWith(['a'])
    expect(store.deletedChatIds).toEqual([])
  })

  it('回收站恢复等待同步空闲超时后中止，不提交恢复请求', async () => {
    store.deletedChatIds = ['a']
    store.chats = { cur: [{ role: 'user', content: 'C' }] }
    store.chatVersions = { cur: 1 }
    store.currentChatId = 'cur'
    // 模拟一个永不结束的上传请求
    saveChatHistory.mockImplementation(() => new Promise(() => {}))
    store.addMessage('cur', { role: 'assistant', content: 'R' })
    await vi.advanceTimersByTimeAsync(500)
    const resultPromise = store.restoreChatsFromTrash(['a'])
    await vi.advanceTimersByTimeAsync(5100)
    expect(await resultPromise).toEqual({ success: false, busy: true })
    expect(restoreTrash).not.toHaveBeenCalled()
    expect(store.deletedChatIds).toEqual(['a'])
  })

  it('首屏历史未加载完成时恢复返回 busy，不覆盖本地恢复草稿', async () => {
    store.isChatHistoryLoaded = false
    store.deletedChatIds = ['a']
    const resultPromise = store.restoreChatsFromTrash(['a'])
    await vi.advanceTimersByTimeAsync(5100)
    expect(await resultPromise).toEqual({ success: false, busy: true })
    expect(restoreTrash).not.toHaveBeenCalled()
    expect(store.deletedChatIds).toEqual(['a'])
  })

  it('在途强制刷新成功后不消费后到强制请求的标记，并立即补拉最新快照', async () => {
    let resolveFirst
    loadChatSummaries
      .mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
      .mockResolvedValueOnce({
        success: true,
        version: 21,
        foldersVersion: 0,
        chatMeta: { a: {}, b: {} },
        deletedChatIds: [],
        folders: [],
        summaries: [
          { id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 1 },
          { id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 1 }
        ]
      })
    const first = store.refreshFromServer({ force: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveFirst).toBeTypeOf('function')
    // 第一个强制刷新在途时又发生一次强制刷新需求（如另一个恢复）
    expect(await store.refreshFromServer({ force: true })).toBe(false)
    resolveFirst({
      success: true,
      version: 20,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: 'A', preview: 'A', lastTime: null, count: 1, version: 1 }]
    })
    await first
    await vi.advanceTimersByTimeAsync(0)
    // 旧请求成功后未清除新需求的 pending，并由新一次强制刷新应用最新摘要
    expect(loadChatSummaries).toHaveBeenCalledTimes(2)
    expect(store.chatSummaries.b).toBeDefined()
  })

  it('单会话正文合并期间删除当前会话时丢弃迟到响应，不复活已删会话', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.chats = {
      a: [{ role: 'user', content: '问题' }],
      cur: [{ role: 'user', content: '另一个会话' }]
    }
    store.chatMeta = { a: {}, cur: {} }
    store.chatVersions = { a: 5, cur: 1 }
    store.currentChatId = 'a'
    loadChatVersion.mockResolvedValue({ success: true, version: 99 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 99,
      foldersVersion: 0,
      chatMeta: { a: {}, cur: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: '问题', lastTime: null, count: 2, version: 6 },
        { id: 'cur', title: 'C', preview: '另一个会话', lastTime: null, count: 1, version: 1 }
      ]
    })
    let resolveSingle
    loadSingleChatHistory.mockImplementation(() => new Promise(resolve => { resolveSingle = resolve }))
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 100, versions: {}, foldersVersion: 0, conflicts: []
    }))
    const pending = store.refreshFromServer()
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveSingle).toBeTypeOf('function')
    // 单会话正文请求在途时删除当前会话并完成保存
    store.deleteChat('a')
    await flushSync()
    resolveSingle({
      success: true,
      messages: [{ role: 'user', content: '问题' }, { role: 'assistant', content: '其他端回答' }],
      meta: {},
      version: 6,
      exists: true
    })
    await pending
    expect(store.chats.a).toBeUndefined()
    expect(store.deletedChatIds).toContain('a')
  })

  it('恢复的空会话在侧边栏临时可见', async () => {
    store.deletedChatIds = ['a']
    restoreTrash.mockResolvedValue({ success: true, restored: ['a'], versions: { a: 2 } })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 5,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: '新会话', preview: '', lastTime: null, count: 0, version: 2 }]
    })
    await store.restoreChatsFromTrash(['a'])
    expect(store.chatSummaries.a).toBeDefined()
    expect(store.sortedChatList.total).toBe(1)
  })

  it('服务端忽略陈旧删除声明时立即解除本地删除态并强制对账', async () => {
    store.deletedChatIds = ['a']
    store.deletedChatIdVersions = { a: 1 }
    saveChatHistory.mockResolvedValue({
      success: true, version: 20, versions: {}, foldersVersion: 0, conflicts: [], ignoredDeleteIds: ['a']
    })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 21,
      foldersVersion: 0,
      chatMeta: { a: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'a', title: '已恢复A', preview: 'A', lastTime: null, count: 1, version: 2 }]
    })
    store.switchChat('x')
    await flushSync()
    await vi.advanceTimersByTimeAsync(0)
    expect(store.deletedChatIds).toEqual([])
    expect(store.chatSummaries.a).toMatchObject({ title: '已恢复A' })
  })

  it('服务端同步版本回退到0时收敛本地基准，避免自动同步永久停摆', async () => {
    // 先通过保存响应把本地已知服务端版本推进到 100
    saveChatHistory.mockImplementation(async () => ({
      success: true, version: 100, versions: {}, foldersVersion: 0, conflicts: []
    }))
    store.currentChatId = 'x'
    store.switchChat('x')
    await flushSync()
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 0,
      foldersVersion: 0,
      chatMeta: { b: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 1 }]
    })
    // 第一次发现摘要版本(0)低于本地基准：抑制立即重试，等待下一轮
    await store.refreshFromServer({ force: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(loadChatSummaries).toHaveBeenCalledTimes(1)
    expect(store.chatSummaries.b).toBeUndefined()
    // 连续第二次回退：收敛 remoteVersion 并自动补拉，摘要最终被应用
    await store.refreshFromServer({ force: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(loadChatSummaries).toHaveBeenCalledTimes(3)
    expect(store.chatSummaries.b).toBeDefined()
  })

  it('单会话正文合并期间切换会话不会丢弃该会话的有效响应', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.chats = {
      a: [{ role: 'user', content: '问题' }],
      cur: [{ role: 'user', content: '另一个会话' }]
    }
    store.chatMeta = { a: {}, cur: {} }
    store.chatVersions = { a: 5, cur: 1 }
    store.currentChatId = 'a'
    loadChatVersion.mockResolvedValue({ success: true, version: 99 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 99,
      foldersVersion: 0,
      chatMeta: { a: {}, cur: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: '问题', lastTime: null, count: 2, version: 6 },
        { id: 'cur', title: 'C', preview: '另一个会话', lastTime: null, count: 1, version: 1 }
      ]
    })
    let resolveSingle
    loadSingleChatHistory.mockImplementation(() => new Promise(resolve => { resolveSingle = resolve }))
    const pending = store.refreshFromServer()
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveSingle).toBeTypeOf('function')
    // 正文请求在途时切换到其他会话
    store.switchChat('cur')
    resolveSingle({
      success: true,
      messages: [{ role: 'user', content: '问题' }, { role: 'assistant', content: '其他端回答' }],
      meta: {},
      version: 6,
      exists: true
    })
    await pending
    // 切换会话属于无关变更，不应丢弃 a 的有效正文合并
    expect(store.chats.a).toHaveLength(2)
    expect(store.chatVersions.a).toBe(6)
  })

  it('刷新期间当前会话产生本地消息时放弃正文合并，不覆盖未上传内容', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    store.chats = {
      a: [{ role: 'user', content: '问题' }],
      cur: [{ role: 'user', content: '另一个会话' }]
    }
    store.chatMeta = { a: {}, cur: {} }
    store.chatVersions = { a: 5, cur: 1 }
    store.currentChatId = 'a'
    loadChatVersion.mockResolvedValue({ success: true, version: 99 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 99,
      foldersVersion: 0,
      chatMeta: { a: {}, cur: {} },
      deletedChatIds: [],
      folders: [],
      summaries: [
        { id: 'a', title: 'A', preview: '问题', lastTime: null, count: 2, version: 6 },
        { id: 'cur', title: 'C', preview: '另一个会话', lastTime: null, count: 1, version: 1 }
      ]
    })
    let resolveSingle
    loadSingleChatHistory.mockImplementation(() => new Promise(resolve => { resolveSingle = resolve }))
    const pending = store.refreshFromServer()
    await vi.advanceTimersByTimeAsync(0)
    expect(resolveSingle).toBeTypeOf('function')
    // 正文请求在途时用户在当前会话新增消息（尚未上传）
    store.addMessage('a', { role: 'user', content: '本地新消息' })
    resolveSingle({
      success: true,
      messages: [{ role: 'user', content: '问题' }, { role: 'assistant', content: '其他端回答' }],
      meta: {},
      version: 6,
      exists: true
    })
    await pending
    // 本地新消息必须保留，不能被服务端快照整体替换
    expect(store.chats.a).toHaveLength(2)
    expect(store.chats.a.some(message => message.content === '本地新消息')).toBe(true)
  })

  it('回收站变更同步入口：handled 事件不重复刷新，彻底删除走普通刷新', async () => {
    const { loadChatVersion } = await import('@/api/chat')
    expect(await store.syncSidebarAfterTrashChange({ restored: ['a'], handled: true })).toBe(false)
    expect(loadChatVersion).not.toHaveBeenCalled()
    expect(loadChatSummaries).not.toHaveBeenCalled()

    // 彻底删除无 payload：普通版本号刷新
    loadChatVersion.mockResolvedValue({ success: true, version: 13 })
    loadChatSummaries.mockResolvedValue({
      success: true,
      version: 13,
      foldersVersion: 0,
      chatMeta: {},
      deletedChatIds: [],
      folders: [],
      summaries: [{ id: 'b', title: 'B', preview: 'B', lastTime: null, count: 1, version: 1 }]
    })
    await store.syncSidebarAfterTrashChange()
    expect(loadChatVersion).toHaveBeenCalledTimes(1)
    expect(loadChatSummaries).toHaveBeenCalledTimes(1)
    expect(store.chatSummaries.b).toBeDefined()
  })
})

describe('回答版本历史', () => {
  let store

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    store = useChatStore()
    store.isChatHistoryLoaded = true
    saveChatHistory.mockClear()
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 1, versions: {}, foldersVersion: 0, conflicts: [] }))
  })

  afterEach(() => {
    store.$dispose()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('旧数据无损升级：无 versions 的 assistant 消息读取时规范化为单版本', () => {
    store.chats = { a: [{ role: 'user', content: '问' }, { role: 'assistant', content: '旧回答', modelName: 'M1' }] }
    const info = store.getMessageVersions('a', 1)
    expect(info.total).toBe(1)
    expect(info.list[0].modelName).toBe('M1')
    // 单版本不视为多版本历史
    expect(info.total < 2).toBe(true)
  })

  it('重新生成追加新版本：成功时切换为当前版本，旧版本保留且平铺字段更新', () => {
    store.chats = { a: [{ role: 'user', content: '问' }, { role: 'assistant', content: '旧回答' }] }
    const vid = store.addMessageVersion('a', 1, {
      content: '新回答', modelName: 'M2', time: '2026-08-26 12:00:00', status: 'done'
    }, true)
    expect(vid).toBeTruthy()
    const msg = store.chats.a[1]
    expect(msg.versions).toHaveLength(2)
    expect(msg.currentVersionId).toBe(vid)
    expect(msg.content).toBe('新回答')
    expect(msg.versions[0].content).toBe('旧回答')
  })

  it('中断的重新生成保留部分结果为新版本但不覆盖当前版本', () => {
    store.chats = { a: [{ role: 'user', content: '问' }, { role: 'assistant', content: '旧回答' }] }
    const originalVersionId = store.getMessageVersions('a', 1).list[0].versionId
    store.addMessageVersion('a', 1, {
      content: '写到一半中断', status: 'offline'
    }, false)
    const msg = store.chats.a[1]
    expect(msg.versions).toHaveLength(2)
    expect(msg.currentVersionId).toBe(originalVersionId)
    expect(msg.content).toBe('旧回答', '中断版本不得覆盖当前展示内容')
  })

  it('版本切换：平铺字段同步为目标版本内容，token 快照随版本走', () => {
    store.chats = { a: [{ role: 'user', content: '问' }, { role: 'assistant', content: 'V1' }] }
    const vid = store.addMessageVersion('a', 1, {
      content: 'V2', status: 'done',
      usage: { promptTokens: 100, completionTokens: 50, reasoningTokens: 5, cachedTokens: 0 }
    }, true)
    expect(store.chats.a[1].content).toBe('V2')
    expect(store.chats.a[1].completionTokens).toBe(50)

    // 切回旧版本：展示与用量快照恢复为旧版本的值
    const firstVid = store.chats.a[1].versions[0].versionId
    expect(store.selectMessageVersion('a', 1, firstVid)).toBe(true)
    expect(store.chats.a[1].content).toBe('V1')

    // 重复切换同一版本返回 false（无操作）
    expect(store.selectMessageVersion('a', 1, firstVid)).toBe(false)
    // 切不存在的版本返回 false
    expect(store.selectMessageVersion('a', 1, 'v_not_exist')).toBe(false)
    expect(vid).toBeTruthy()
  })
})
