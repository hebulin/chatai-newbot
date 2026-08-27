// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'
import { saveChatHistory, loadSingleChatHistory } from '@/api/chat'

vi.mock('@/api/chat', () => ({
  loadChatHistory: vi.fn(),
  loadChatSummaries: vi.fn(),
  loadChatVersion: vi.fn(),
  loadSingleChatHistory: vi.fn(),
  saveChatHistory: vi.fn(async () => ({ success: true, version: 1 }))
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
    saveChatHistory.mockClear()
    saveChatHistory.mockImplementation(async () => ({ success: true, version: 2, versions: {}, foldersVersion: 1, conflicts: [] }))
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
