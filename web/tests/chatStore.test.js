// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useChatStore } from '@/stores/chat'

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
