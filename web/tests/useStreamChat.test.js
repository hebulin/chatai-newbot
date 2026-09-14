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
        for (const c of chunks) controller.enqueue(typeof c === 'string' ? encoder.encode(c) : c)
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
  it('长输出和无换行尾块均完整保留，结束原因为 stop 时正常完成', async () => {
    const content = '长内容 data: 中文🙂\n'.repeat(10000)
    const wire = sse({ choices: [{ delta: { content } }] })
      + 'data: ' + JSON.stringify({ choices: [{ delta: { content: '最后一段' }, finish_reason: 'stop' }], usage: { completion_tokens: 65536 } })
    const chunks = []
    const bytes = new TextEncoder().encode(wire)
    for (let index = 0; index < bytes.length; index += 37) chunks.push(bytes.slice(index, index + 37))
    globalThis.fetch = makeFetch(chunks)
    const sc = useStreamChat()
    const done = vi.fn()
    await sc.send({}, { onDone: done })
    expect(done).toHaveBeenCalledTimes(1)
    expect(done.mock.calls[0][0]).toMatchObject({ content: content + '最后一段', status: 'done', usage: { completion_tokens: 65536 } })
  })

  it('达到输出上限后保留思考、正文和最终 usage，DONE 不会误判为完整', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { reasoning_content: '思考内容', content: '未完正文' } }] }),
      sse({ choices: [{ finish_reason: 'length' }] }),
      sse({ usage: { completion_tokens: 65536 } }),
      'data: [DONE]'
    ])
    const sc = useStreamChat()
    const done = vi.fn()
    await sc.send({}, { chatId: 'length', onDone: done })
    expect(done.mock.calls[0][0]).toMatchObject({ status: 'length', content: '未完正文', reasoning_content: '思考内容', interrupted: true, usage: { completion_tokens: 65536 } })
    expect(done.mock.calls[0][0].error).toContain('上限')
    expect(sc.loadDraft('length').content).toBe('未完正文')
    expect(sc.loadDraft('length').notice).toBe(done.mock.calls[0][0].notice)
    expect(sc.loadDraft('length').notice).toContain('上限')
  })

  /** 无信号 EOF 必须保留中断状态和草稿，不能仅凭已有内容判为成功。 */
  it.each([
    { content: '部分内容' },
    { reasoning_content: '只有思考内容' },
    { content: '部分正文', reasoning_content: '部分思考' }
  ])('无完成信号时正常关闭保留部分内容：%j', async delta => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta }] }),
      sse({ usage: { prompt_tokens: 12, completion_tokens: 6 } })
    ])
    const done = vi.fn()
    const onError = vi.fn()
    const sc = useStreamChat()
    await sc.send({}, { chatId: 'missing-done', onDone: done, onError })
    expect(done).toHaveBeenCalledTimes(1)
    expect(onError).not.toHaveBeenCalled()
    const result = done.mock.calls[0][0]
    expect(result).toMatchObject({ ...delta, status: 'failed', interrupted: true,
      usage: { prompt_tokens: 12, completion_tokens: 6 } })
    expect(result.notice).toContain('未收到回答完成信号')
    expect(sc.loadDraft('missing-done')).toMatchObject({ ...delta, status: 'failed',
      notice: result.notice, usage: result.usage })
  })

  /** 结束事件缺字或被截断时不能从损坏 JSON 中推断正常完成。 */
  it('完成事件 JSON 被截断时按中断保留草稿', async () => {
    globalThis.fetch = makeFetch([
      sse({ choices: [{ delta: { content: '保留这段正文' } }] }),
      'data: {"choices":[{"finish_reason":"stop"'
    ])
    const done = vi.fn()
    const sc = useStreamChat()
    await sc.send({}, { chatId: 'broken-tail', onDone: done })
    expect(done.mock.calls[0][0]).toMatchObject({ status: 'failed', interrupted: true, content: '保留这段正文' })
    expect(sc.loadDraft('broken-tail').content).toBe('保留这段正文')
  })

  /** 两类明确完成信号均接受，正常完成才清除草稿。 */
  it.each(['data: [DONE]', sse({ choices: [{ delta: {}, finish_reason: 'stop' }] })])(
    '收到明确完成信号正常结算并清除草稿：%s', async ending => {
      globalThis.fetch = makeFetch([sse({ choices: [{ delta: { content: '完整回答' } }] }), ending])
      const done = vi.fn()
      const sc = useStreamChat()
      await sc.send({}, { chatId: 'complete', onDone: done })
      expect(done).toHaveBeenCalledTimes(1)
      expect(done.mock.calls[0][0]).toMatchObject({ status: 'done', content: '完整回答' })
      expect(sc.loadDraft('complete')).toBeNull()
    }
  )

  it('既无完成信号也无任何内容时正常关闭判为失败', async () => {
    globalThis.fetch = makeFetch([])
    const onError = vi.fn()
    const sc = useStreamChat()
    await sc.send({}, { onError })
    expect(sc.status.value).toBe('failed')
    expect(onError).toHaveBeenCalledTimes(1)
  })

  it('流内错误后即使收到 DONE 仍判为失败', async () => {
    globalThis.fetch = makeFetch([sse({ error: { message: '上游错误' } }), 'data: [DONE]\n'])
    const onError = vi.fn()
    const sc = useStreamChat()
    await sc.send({}, { onError })
    expect(sc.status.value).toBe('failed')
    expect(onError).toHaveBeenCalledTimes(1)
  })
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
