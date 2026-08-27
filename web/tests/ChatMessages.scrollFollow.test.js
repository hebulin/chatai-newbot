import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive } from 'vue'

import ChatMessages from '@/components/chat/ChatMessages.vue'

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: key => key })
}))

vi.mock('@/composables/useMarkdown', () => ({
  renderMermaidBlocks: vi.fn(),
  processSpecialContent: vi.fn(),
  handleMermaidToolbarClick: vi.fn()
}))

vi.mock('@/composables/useSearchHighlight', () => ({
  useSearchHighlight: () => ({
    renderMd: text => `<p>${String(text || '')}</p>`,
    formatUserContent: text => String(text || '')
  })
}))

vi.mock('@/composables/useSpeech', () => ({
  useSpeech: () => ({
    speechSupported: false,
    status: { value: 'idle' },
    isCurrent: () => false,
    toggle: vi.fn()
  })
}))

vi.mock('@/composables/useTheme', () => ({
  useTheme: () => ({ getTheme: () => 'light' })
}))

vi.mock('@/stores/models', () => ({
  useModelsStore: () => ({ botAvatarSvg: '' })
}))

vi.mock('@/stores/chat', () => ({
  useChatStore: () => ({
    currentChatId: 'chat-1',
    getMessageVersions: () => null
  })
}))

vi.mock('@/api/user', () => ({
  getUserProfile: vi.fn().mockResolvedValue({ success: false })
}))

const mountedApps = []

// 为 jsdom 元素模拟浏览器会自动夹紧的滚动几何数据
function mockScrollGeometry(element, initial = {}) {
  const geometry = {
    scrollHeight: initial.scrollHeight ?? 1000,
    clientHeight: initial.clientHeight ?? 400,
    scrollTop: initial.scrollTop ?? 0
  }
  Object.defineProperties(element, {
    scrollHeight: { configurable: true, get: () => geometry.scrollHeight },
    clientHeight: { configurable: true, get: () => geometry.clientHeight },
    scrollTop: {
      configurable: true,
      get: () => geometry.scrollTop,
      set: value => {
        const max = Math.max(0, geometry.scrollHeight - geometry.clientHeight)
        geometry.scrollTop = Math.max(0, Math.min(Number(value) || 0, max))
      }
    }
  })
  return geometry
}

// 挂载普通流式或重新生成原位流式消息，并返回可更新的父级状态
function mountStreamingMessages(regen) {
  const state = reactive({
    messages: regen ? [{ role: 'assistant', content: '旧回答' }] : [],
    isStreaming: true,
    streamingMsg: {
      role: 'assistant',
      content: '',
      reasoning_content: '第一段思考',
      thinkingTime: 0,
      modelName: '测试模型'
    },
    regenIdx: regen ? 0 : -1
  })
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({
    setup() {
      return () => h(ChatMessages, {
        messages: state.messages,
        isStreaming: state.isStreaming,
        streamingMsg: state.streamingMsg,
        regenIdx: state.regenIdx,
        startIndex: 0
      })
    }
  })
  app.mount(host)
  mountedApps.push({ app, host })
  return { state, host }
}

// 等待 Vue DOM、MutationObserver 与滚动 rAF 队列全部完成
async function flushScrollUpdates(ms = 100) {
  await nextTick()
  await Promise.resolve()
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('requestAnimationFrame', callback => setTimeout(() => callback(Date.now()), 16))
  vi.stubGlobal('cancelAnimationFrame', id => clearTimeout(id))
})

afterEach(() => {
  while (mountedApps.length) {
    const { app, host } = mountedApps.pop()
    app.unmount()
    host.remove()
  }
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe.each([
  ['普通流式输出', false],
  ['重新生成原位流式输出', true]
])('思考内容滚动跟随：%s', (_name, regen) => {
  it('自动贴底，并支持上滚打断与回到底部恢复', async () => {
    const { state, host } = mountStreamingMessages(regen)
    await nextTick()
    const thinkingBody = host.querySelector('[data-streaming-thinking]')
    expect(thinkingBody).not.toBeNull()
    const geometry = mockScrollGeometry(thinkingBody)

    state.streamingMsg = { ...state.streamingMsg, reasoning_content: '第二段思考' }
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(600)

    geometry.scrollTop = 220
    thinkingBody.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    thinkingBody.dispatchEvent(new Event('scroll'))
    geometry.scrollHeight = 1100
    state.streamingMsg = { ...state.streamingMsg, reasoning_content: '第三段思考' }
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(220)

    geometry.scrollTop = 690
    thinkingBody.dispatchEvent(new Event('scroll'))
    geometry.scrollHeight = 1200
    state.streamingMsg = { ...state.streamingMsg, reasoning_content: '第四段思考' }
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(800)
  })
})
