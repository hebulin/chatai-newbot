// @vitest-environment jsdom
import { ref } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const { elementMessage, elementMessageBox } = vi.hoisted(() => ({
  elementMessage: {
    warning: vi.fn(),
    error: vi.fn(),
    success: vi.fn(),
    info: vi.fn()
  },
  elementMessageBox: {
    confirm: vi.fn(() => Promise.resolve()),
    prompt: vi.fn(() => Promise.resolve({ value: '已编辑' }))
  }
}))

vi.mock('element-plus', () => ({ ElMessage: elementMessage, ElMessageBox: elementMessageBox }))
vi.mock('@/api/chat', () => ({
  generateChatTitle: vi.fn(),
  loadChatHistory: vi.fn(),
  loadChatSummaries: vi.fn(),
  loadChatVersion: vi.fn(),
  loadSingleChatHistory: vi.fn(),
  saveChatHistory: vi.fn(async () => ({ success: true, version: 1 }))
}))

import { useChatStreaming } from '@/composables/chat/useChatStreaming'
import { useChatMessageActions } from '@/composables/chat/useChatMessageActions'
import { useChatStore } from '@/stores/chat'
import { saveChatHistory } from '@/api/chat'

/** 创建流式 composable 使用的最小状态与依赖替身。 */
function createStreamingHarness() {
  const chatStore = {
    currentChatId: 'chat-1',
    chats: { 'chat-1': [{ role: 'user', content: '你好' }] },
    chatMeta: { 'chat-1': {} },
    addMessage: vi.fn((chatId, message) => chatStore.chats[chatId].push(message)),
    addMessageVersion: vi.fn(),
    suspendSync: vi.fn(),
    resumeSync: vi.fn(),
    syncCurrentChatFromServer: vi.fn(() => Promise.resolve(false))
  }
  const modelsStore = {
    currentModelId: 'model-1',
    currentModelName: '测试模型'
  }
  const streamChat = {
    isStreaming: ref(false),
    send: vi.fn(),
    stop: vi.fn()
  }
  const scrollFollow = {
    scrollToBottomImmediate: vi.fn(),
    syncScrollToBottom: vi.fn(),
    updateNavButtons: vi.fn()
  }
  const onGenerateTitle = vi.fn()
  const streaming = useChatStreaming({
    chatStore,
    modelsStore,
    streamChat,
    scrollFollow,
    t: key => key,
    onGenerateTitle
  })
  return { chatStore, modelsStore, streamChat, scrollFollow, onGenerateTitle, streaming }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('聊天流式状态机', () => {
  it('正常结算回答、用量与当前轮输入 Token 且仅触发一次自动标题', async () => {
    const harness = createStreamingHarness()
    harness.streamChat.send.mockImplementation(async (body, handlers) => {
      handlers.onDone({
        status: 'done',
        content: '回答',
        reasoning_content: '思考',
        usage: {
          prompt_tokens: 20,
          completion_tokens: 8,
          prompt_tokens_details: { cached_tokens: 3 },
          completion_tokens_details: { reasoning_tokens: 2 }
        }
      })
    })

    await harness.streaming.startStream('chat-1', true)

    const answer = harness.chatStore.chats['chat-1'][1]
    expect(answer).toMatchObject({
      content: '回答', promptTokens: 20, completionTokens: 8, cachedTokens: 3, reasoningTokens: 2,
      turnInputTokens: 20
    })
    expect(harness.chatStore.chats['chat-1'][0].promptTokens).toBe(20)
    expect(harness.onGenerateTitle).toHaveBeenCalledTimes(1)
    expect(harness.streaming.streamingMsg.value).toBeNull()
  })

  it('重新生成中断时只追加非当前版本并保留部分用量', async () => {
    const harness = createStreamingHarness()
    harness.chatStore.chats['chat-1'].push({ role: 'assistant', content: '旧回答' })
    harness.streamChat.send.mockImplementation(async (body, handlers) => {
      handlers.onDone({
        status: 'stopped',
        content: '部分新回答',
        usage: { prompt_tokens: 12, completion_tokens: 4 }
      })
    })

    await harness.streaming.startStream('chat-1', false, {
      regenTarget: { chatId: 'chat-1', msgIdx: 1 }
    })

    expect(harness.chatStore.addMessageVersion).toHaveBeenCalledWith(
      'chat-1',
      1,
      expect.objectContaining({
        content: '部分新回答',
        status: 'stopped',
        usage: expect.objectContaining({ promptTokens: 12, completionTokens: 4 })
      }),
      false
    )
    expect(harness.chatStore.addMessage).not.toHaveBeenCalled()
  })
})

describe('回答版本与分支操作', () => {
  it('重新生成将展示下标换算为绝对下标并在完成后恢复同步', async () => {
    const chatStore = {
      currentChatId: 'chat-1',
      chats: { 'chat-1': [{ role: 'user', content: '问题' }, { role: 'assistant', content: '回答' }] },
      chatMeta: { 'chat-1': {} },
      suspendSync: vi.fn(),
      resumeSync: vi.fn()
    }
    const startStream = vi.fn(() => Promise.resolve())
    const actions = useChatMessageActions({
      chatStore,
      modelsStore: { currentModelId: 'model-1' },
      streamChat: { isStreaming: ref(false) },
      scrollFollow: { scrollToBottomImmediate: vi.fn() },
      hiddenCount: ref(1),
      isDeepThinking: ref(true),
      startStream,
      t: key => key
    })

    await actions.handleRegenerate(0)

    expect(startStream).toHaveBeenCalledWith('chat-1', true, {
      regenTarget: { chatId: 'chat-1', msgIdx: 1 }
    })
    expect(chatStore.suspendSync).toHaveBeenCalledTimes(1)
    expect(chatStore.resumeSync).toHaveBeenCalledTimes(1)
  })
})

describe('编辑重发沿用当前会话回答版本', () => {
  let harness
  let actions
  let hiddenCount

  beforeEach(() => {
    vi.useFakeTimers()
    localStorage.clear()
    setActivePinia(createPinia())
    harness = createStreamingHarness()
    harness.chatStore = useChatStore()
    harness.chatStore.isChatHistoryLoaded = true
    harness.chatStore.currentChatId = 'chat-1'
    harness.chatStore.chats = { 'chat-1': [
      { id: 'user-1', role: 'user', content: '原问题', images: ['/image'], attachments: [{ name: 'a.txt', url: '/file' }] },
      { id: 'reply-1', role: 'assistant', content: '原回答', completionTokens: 6 }
    ] }
    harness.streaming = useChatStreaming({ ...harness, t: key => key })
    hiddenCount = ref(0)
    actions = useChatMessageActions({
      ...harness, hiddenCount, isDeepThinking: ref(true),
      startStream: harness.streaming.startStream, t: key => key
    })
    harness.streamChat.send.mockImplementation(async (body, handlers) => {
      expect(harness.streaming.regenStreamingIdx.value).toBe(1)
      handlers.onDone({ status: 'done', content: '新回答', usage: { completion_tokens: 8 } })
    })
  })

  afterEach(() => {
    harness.chatStore.$dispose()
    vi.clearAllTimers()
    vi.useRealTimers()
  })

  it('原位编辑并追加分页版本，保留附件、消息标识且保存同一会话', async () => {
    await actions.handleEditResend(0)
    const store = harness.chatStore
    expect(store.currentChatId).toBe('chat-1')
    expect(Object.keys(store.chats)).toEqual(['chat-1'])
    expect(store.chats['chat-1']).toHaveLength(2)
    expect(store.chats['chat-1'][0]).toMatchObject({ id: 'user-1', content: '已编辑', images: ['/image'], attachments: [{ name: 'a.txt' }] })
    expect(store.chats['chat-1'][1]).toMatchObject({ id: 'reply-1', content: '新回答', completionTokens: 8 })
    expect(store.chats['chat-1'][1].versions.map(v => v.content)).toEqual(['原回答', '新回答'])
    expect(store.getMessageVersions('chat-1', 1)).toMatchObject({ total: 2, currentIndex: 1 })
    expect(harness.streamChat.send.mock.calls[0][0]).toMatchObject({
      chatId: 'chat-1', deepThinking: true,
      messages: [{ role: 'user', content: '已编辑', images: ['/image'], attachments: [{ name: 'a.txt', url: '/file' }] }]
    })
    await vi.advanceTimersByTimeAsync(600)
    expect(saveChatHistory.mock.calls.at(-1)[0].chats['chat-1'][1].versions).toHaveLength(2)
    expect(harness.streaming.regenStreamingIdx.value).toBe(-1)
    await actions.handleEditResend(0)
    expect(store.getMessageVersions('chat-1', 1).total).toBe(3)
    expect(store.chats['chat-1']).toHaveLength(2)
  })

  it('历史消息使用绝对下标，后续会话和分隔线保留但不参与重发上下文', async () => {
    const messages = harness.chatStore.chats['chat-1']
    messages.unshift({ role: 'user', content: '更早的问题' }, { role: 'assistant', content: '更早的回答' }, { role: 'divider' })
    messages.push({ role: 'user', content: '后续问题' }, { role: 'assistant', content: '后续回答' }, { role: 'divider' })
    hiddenCount.value = 3
    const following = JSON.stringify(messages.slice(5))
    harness.streamChat.send.mockImplementation(async (body, handlers) => {
      expect(harness.streaming.regenStreamingIdx.value).toBe(4)
      expect(body.messages.map(m => m.content)).toEqual(['已编辑'])
      handlers.onDone({ status: 'done', content: '历史问题的新回答' })
    })
    await actions.handleEditResend(0)
    expect(messages[4].versions.map(v => v.content)).toEqual(['原回答', '历史问题的新回答'])
    expect(JSON.stringify(messages.slice(5))).toBe(following)
    expect(Object.keys(harness.chatStore.chats)).toEqual(['chat-1'])
  })

  it('取消编辑或弹窗期间切换会话时不修改消息、不发送请求', async () => {
    elementMessageBox.prompt.mockRejectedValueOnce('cancel')
    await actions.handleEditResend(0)
    expect(harness.chatStore.chats['chat-1'][0].content).toBe('原问题')
    elementMessageBox.prompt.mockImplementationOnce(async () => {
      harness.chatStore.currentChatId = 'chat-2'
      return { value: '不得写入' }
    })
    await actions.handleEditResend(0)
    expect(harness.chatStore.chats['chat-1'][0].content).toBe('原问题')
    expect(harness.streamChat.send).not.toHaveBeenCalled()
  })

  it('重发失败保留原回答，部分中断只追加非当前版本', async () => {
    harness.streamChat.send.mockImplementationOnce(async (body, handlers) => handlers.onError(new Error('断网')))
    await actions.handleEditResend(0)
    expect(harness.chatStore.chats['chat-1'][1].content).toBe('原回答')
    expect(harness.chatStore.chats['chat-1']).toHaveLength(2)
    harness.streamChat.send.mockImplementationOnce(async (body, handlers) => handlers.onDone({ status: 'stopped', content: '部分回答' }))
    await actions.handleEditResend(0)
    expect(harness.chatStore.chats['chat-1'][1].content).toBe('原回答')
    expect(harness.chatStore.getMessageVersions('chat-1', 1)).toMatchObject({ total: 2, currentIndex: 0 })
    await vi.advanceTimersByTimeAsync(600)
    expect(saveChatHistory).toHaveBeenCalled()
  })

  it('尚无对应回答时在该问题之后补齐第一条回答，不吞掉后续消息', async () => {
    const messages = harness.chatStore.chats['chat-1']
    messages.splice(1, 1, { role: 'user', content: '后续待回答问题', promptTokens: 99 })
    harness.streamChat.send.mockImplementationOnce(async (body, handlers) => {
      expect(body.messages.map(m => m.content)).toEqual(['已编辑'])
      handlers.onDone({ status: 'done', content: '补齐回答', usage: { prompt_tokens: 12 } })
    })
    await actions.handleEditResend(0)
    expect(messages.map(m => m.content)).toEqual(['已编辑', '补齐回答', '后续待回答问题'])
    expect(messages[0].promptTokens).toBe(12)
    expect(messages[2].promptTokens).toBe(99)
    expect(harness.chatStore.getMessageVersions('chat-1', 1).total).toBe(1)
  })

  it('原回答是错误气泡时成功重发恢复正常回答，历史错误仍可切换', async () => {
    const store = harness.chatStore
    store.chats['chat-1'][1].isError = true
    await actions.handleEditResend(0)
    expect(store.chats['chat-1'][1].isError).toBeUndefined()
    const originalVersion = store.chats['chat-1'][1].versions[0].versionId
    store.selectMessageVersion('chat-1', 1, originalVersion)
    expect(store.chats['chat-1'][1]).toMatchObject({ content: '原回答', isError: true })
  })
})
