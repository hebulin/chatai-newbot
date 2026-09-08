// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useStreamChat, STREAM_STATUS } from '@/composables/useStreamChat'

// mock vue-router（useStreamChat 内部使用 useRouter 处理 401）
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
}))

/** 构造一个可控的 SSE fetch mock：按块 enqueue，abort 信号联动到流（模拟真实 fetch 的中断行为） */
function makeFetch(chunks, { closeAfter = true } = {}) {
  return vi.fn((url, init) => Promise.resolve({
    ok: true,
    status: 200,
    text: async () => '',
    body: new ReadableStream({
      start(controller) {
        const encoder = new TextEncoder()
        for (const c of chunks) controller.enqueue(encoder.encode(c))
        if (closeAfter) controller.close()
        // 真实 fetch 中 abort 会取消 body 流并使 read() 抛出 AbortError，这里模拟该行为
        init?.signal?.addEventListener('abort', () => {
          try {
            controller.error(new DOMException('The operation was aborted.', 'AbortError'))
          } catch (e) { /* 流已关闭时忽略 */ }
        })
      }
    })
  }))
}

/** 构造 SSE data 行 */
function sse(obj) {
  return `data: ${JSON.stringify(obj)}\n\n`
}

describe('useStreamChat 流式状态与断网内容保留', () => {
  beforeEach(() => {
    localStorage.clear()
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('正常完成：状态为 done，正文与用量完整回调', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '你好' } }] }),
      sse({ choices: [{ delta: { content: '，世界' } }], usage: { prompt_tokens: 10, completion_tokens: 5 } }),
      'data: [DONE]\n\n'
    ])

    const stream = useStreamChat()
    let doneData = null
    await stream.send({ modelConfigId: 'm1' }, { chatId: 'c1', onDone: d => { doneData = d } })

    expect(stream.status.value).toBe(STREAM_STATUS.DONE)
    // 回归：onDone 载荷的 status 必须是终态 done，不得残留 settle 前的 streaming
    // （此前 buildResult 在 settle 前构造导致 status=streaming，被误判为 interrupted）
    expect(doneData.status).toBe('done')
    expect(doneData.content).toBe('你好，世界')
    expect(doneData.usage.prompt_tokens).toBe(10)
    expect(doneData.error).toBeUndefined()
    // 正常完成后草稿被清除
    expect(stream.loadDraft('c1')).toBeNull()
  })

  it('断网中断：保留已生成正文与思考，状态为 offline', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { reasoning_content: '思考中' } }] }),
      sse({ choices: [{ delta: { content: '部分正文' } }] })
    ], { closeAfter: false })

    const sc = useStreamChat()
    let doneData = null
    const sendPromise = sc.send({ modelConfigId: 'm1' }, { chatId: 'c2', onDone: d => { doneData = d } })
    await vi.advanceTimersByTimeAsync(0)
    // 模拟浏览器掉线
    window.dispatchEvent(new Event('offline'))
    await sendPromise

    expect(sc.status.value).toBe(STREAM_STATUS.OFFLINE)
    expect(doneData.content).toBe('部分正文')
    expect(doneData.reasoning_content).toBe('思考中')
    expect(doneData.interrupted).toBe(true)
    expect(doneData.error).toContain('网络')
  })

  it('用户主动停止：状态为 stopped，已生成内容保留', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '已生成内容' } }] })
    ], { closeAfter: false })

    const sc = useStreamChat()
    let doneData = null
    const sendPromise = sc.send({ modelConfigId: 'm1' }, { chatId: 'c3', onDone: d => { doneData = d } })
    await vi.advanceTimersByTimeAsync(0)
    sc.stop()
    await sendPromise

    expect(sc.status.value).toBe(STREAM_STATUS.STOPPED)
    expect(doneData.content).toBe('已生成内容')
    expect(doneData.interrupted).toBe(true)
    expect(doneData.error).toBeUndefined()
  })

  it('空闲超时：状态为 timeout，已生成内容保留', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '超时前的内容' } }] })
    ], { closeAfter: false })

    const sc = useStreamChat()
    let doneData = null
    const sendPromise = sc.send({ modelConfigId: 'm1' }, { chatId: 'c4', onDone: d => { doneData = d } })
    await vi.advanceTimersByTimeAsync(0)
    // 推进到流空闲超时（5 分钟）
    await vi.advanceTimersByTimeAsync(5 * 60 * 1000 + 100)
    await sendPromise

    expect(sc.status.value).toBe(STREAM_STATUS.TIMEOUT)
    expect(doneData.content).toBe('超时前的内容')
    expect(doneData.error).toContain('超时')
  })

  it('服务端流内报错：已有正文保留，状态为 failed', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '前半部分' } }] }),
      sse({ error: { message: '上游服务异常' } })
    ])

    const sc = useStreamChat()
    let doneData = null
    await sc.send({ modelConfigId: 'm1' }, {
      chatId: 'c5',
      onDone: d => { doneData = d },
      onError: () => {}
    })

    expect(sc.status.value).toBe(STREAM_STATUS.FAILED)
    expect(doneData.content).toBe('前半部分')
    expect(doneData.error).toBe('上游服务异常')
  })

  it('草稿节流持久化与恢复：中断后可从 localStorage 恢复内容', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '草稿内容' } }] })
    ], { closeAfter: false })

    const sc = useStreamChat()
    const sendPromise = sc.send({ modelConfigId: 'm1' }, { chatId: 'c6', onDone: () => {} })
    await vi.advanceTimersByTimeAsync(0)
    // 推进到草稿节流写入窗口
    await vi.advanceTimersByTimeAsync(1600)
    window.dispatchEvent(new Event('offline'))
    await sendPromise

    const draft = sc.loadDraft('c6')
    expect(draft).not.toBeNull()
    expect(draft.content).toBe('草稿内容')
    expect(draft.chatId).toBe('c6')
    sc.clearDraft('c6')
    expect(sc.loadDraft('c6')).toBeNull()
  })

  it('损坏草稿：loadDraft 返回 null 并清理，不抛异常', () => {
    localStorage.setItem('chatai-stream-draft:anonymous:c7', '{broken json')
    const sc = useStreamChat()
    expect(sc.loadDraft('c7')).toBeNull()
    expect(localStorage.getItem('chatai-stream-draft:anonymous:c7')).toBeNull()
  })
})
