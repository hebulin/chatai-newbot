import { ref } from 'vue'
import { useRouter } from 'vue-router'

/** 首响应超时（毫秒）：发起请求后该时长内未收到响应头则判定服务端无响应，主动中断 */
const FIRST_RESPONSE_TIMEOUT = 60 * 1000
/** 流空闲超时（毫秒）：流式过程中该时长内未收到任何 chunk 则中断（与后端 5 分钟超时对齐） */
const STREAM_IDLE_TIMEOUT = 5 * 60 * 1000

export function useStreamChat() {
  const router = useRouter()
  const isStreaming = ref(false)
  const thinkingContent = ref('')
  const answerContent = ref('')
  const thinkingTime = ref(null)
  const usage = ref(null)
  const error = ref(null)

  let controller = null
  let buffer = ''
  let thinkingStartTime = null
  // 内部中断原因标记：'timeout'/'offline' 时 AbortError 不再视为用户主动中断，按错误展示
  let abortReason = null
  // 超时计时器（首响应 / 流空闲共用一个句柄，每次收到数据时重置）
  let idleTimer = null

  // 结算思考用时：最小记 1 秒，避免思考很快时 round 出 0（falsy）导致
  // 历史消息渲染时误判为“正在思考”且持久化后刷新也无法恢复
  function finalizeThinkingTime() {
    if (thinkingStartTime && !thinkingTime.value) {
      thinkingTime.value = Math.max(1, Math.round((Date.now() - thinkingStartTime) / 1000))
    }
  }

  function reset() {
    buffer = ''
    thinkingContent.value = ''
    answerContent.value = ''
    thinkingTime.value = null
    usage.value = null
    error.value = null
    thinkingStartTime = null
    abortReason = null
    clearIdleTimer()
  }

  /** 清除空闲超时计时器 */
  function clearIdleTimer() {
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  /** 重置空闲超时计时器（发起请求 / 每次收到 chunk 时调用） */
  function resetIdleTimer(timeout, reason) {
    clearIdleTimer()
    idleTimer = setTimeout(() => {
      abortReason = reason
      if (controller) controller.abort()
    }, timeout)
  }

  /** 网络断开监听：流式期间浏览器掉线立即中断请求并给出提示 */
  function onOfflineAbort() {
    abortReason = 'offline'
    if (controller) controller.abort()
  }

  async function send(requestBody, { onUpdate, onDone, onError } = {}) {
    reset()
    // 断网前置检查：避免离线时发起注定失败的请求，直接给出友好提示
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      isStreaming.value = false
      error.value = '当前网络已断开，请检查网络连接后重试'
      if (onError) onError(new Error(error.value))
      return
    }
    isStreaming.value = true
    controller = new AbortController()
    // 流式期间监听浏览器网络状态变化
    window.addEventListener('offline', onOfflineAbort)

    let hasResponse = false
    // 首响应超时：服务端迟迟未返回响应头（网关排队/服务挂起）时主动中断
    resetIdleTimer(FIRST_RESPONSE_TIMEOUT, 'timeout')

    try {
      // 认证凭证存于 HttpOnly Cookie，同域 fetch 自动携带
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestBody),
        signal: controller.signal
      })

      // 401 处理
      if (resp.status === 401) {
        clearIdleTimer()
        window.removeEventListener('offline', onOfflineAbort)
        isStreaming.value = false
        localStorage.removeItem('username')
        localStorage.removeItem('role')
        router.push('/login')
        return
      }

      if (!resp.ok) {
        const text = await resp.text()
        let errMsg = '服务器错误 (' + resp.status + ')'
        try {
          const json = JSON.parse(text)
          if (json.message) errMsg = json.message
          else if (json.error && json.error.message) errMsg = json.error.message
        } catch (e) { /* ignore */ }
        throw new Error(errMsg)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()

      const read = async () => {
        // 进入流读取阶段：超时窗口切换为流空闲超时，每次收到 chunk 重置
        resetIdleTimer(STREAM_IDLE_TIMEOUT, 'timeout')
        const result = await reader.read()
        if (result.done) {
          // 处理 buffer 中残留数据（可能是未以换行结尾的最后一行 data: 事件）
          let remaining = buffer.trim()
          if (remaining.startsWith('data:')) remaining = remaining.slice(5).trim()
          if (remaining && remaining !== '[DONE]' && remaining.startsWith('{')) {
            try {
              const json = JSON.parse(remaining)
              if (json.error) {
                error.value = json.error.message || '未知错误'
              }
            } catch (e) { /* skip */ }
          }
          isStreaming.value = false
          // 流结束时若只有思考没有正文（或正文 delta 未触发结算），补结算思考用时
          finalizeThinkingTime()
          if (onDone) onDone({
            content: answerContent.value,
            reasoning_content: thinkingContent.value,
            thinkingTime: thinkingTime.value,
            usage: usage.value,
            error: error.value
          })
          return
        }

        const chunk = decoder.decode(result.value, { stream: true })
        buffer += chunk
        // 按行解析 SSE：仅保留最后一行（可能不完整）在 buffer 中，其余整行处理。
        // 此前用字面量 'data:' 切分，当模型正文本身包含 'data:' 时会解析错乱、内容丢失。
        const lines = buffer.split('\n')
        buffer = lines.pop() || ''
        let updated = false

        for (const rawLine of lines) {
          const line = rawLine.trim()
          if (!line || !line.startsWith('data:')) continue
          const payload = line.slice(5).trim()
          if (!payload || payload === '[DONE]') continue
          if (payload.startsWith('{')) {
            try {
              const json = JSON.parse(payload)
              if (json.error) {
                // 流内错误不再拼接到正文，统一由 onDone 带出后渲染为错误气泡
                error.value = json.error.message || '未知错误'
                continue
              }
              // 提取usage数据
              if (json.usage) {
                usage.value = json.usage
              }
              if (json.choices && json.choices[0] && json.choices[0].delta) {
                const delta = json.choices[0].delta
                if (!hasResponse) hasResponse = true

                // 兼容不同厂商的思考内容字段
                const deltaThinking = delta.reasoning_content || delta.reasoning
                if (deltaThinking) {
                  if (!thinkingStartTime) thinkingStartTime = Date.now()
                  thinkingContent.value += deltaThinking
                  updated = true
                }
                if (delta.content) {
                  if (thinkingStartTime) finalizeThinkingTime()
                  answerContent.value += delta.content
                  updated = true
                }
              }
            } catch (e) { /* skip parse error */ }
          }
        }

        if (updated && onUpdate) {
          onUpdate({
            content: answerContent.value,
            reasoning_content: thinkingContent.value,
            thinkingTime: thinkingTime.value,
            usage: usage.value
          })
        }

        return read()
      }

      await read()
    } catch (err) {
      isStreaming.value = false
      if (err.name === 'AbortError') {
        // 内部原因中断（超时/掉线）：按错误展示并回调 onError，不视为用户主动中断
        if (abortReason === 'timeout') {
          error.value = '响应超时，请稍后重试或更换模型'
        } else if (abortReason === 'offline') {
          error.value = '网络连接已断开，回答已中断，恢复网络后可重新发送'
        } else {
          // 用户中断：若已进入思考阶段同样补结算思考用时
          finalizeThinkingTime()
          if (onDone) onDone({
            content: answerContent.value,
            reasoning_content: thinkingContent.value,
            thinkingTime: thinkingTime.value,
            usage: usage.value,
            interrupted: true
          })
          return
        }
        finalizeThinkingTime()
        if (onError) onError(new Error(error.value))
        return
      }
      // fetch 网络级失败（TypeError）统一为友好提示，避免暴露 "Failed to fetch" 原文
      if (err instanceof TypeError) {
        error.value = navigator.onLine === false ? '网络连接已断开，请检查网络后重试' : '无法连接到服务器，请稍后重试'
        if (onError) onError(new Error(error.value))
        return
      }
      error.value = err.message
      if (onError) onError(err)
    } finally {
      clearIdleTimer()
      abortReason = null
      window.removeEventListener('offline', onOfflineAbort)
    }
  }

  function stop() {
    if (controller) {
      controller.abort()
      controller = null
    }
  }

  return {
    isStreaming, thinkingContent, answerContent, thinkingTime, usage, error,
    send, stop, reset
  }
}
