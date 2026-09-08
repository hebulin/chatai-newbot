import { ref } from 'vue'
import { useRouter } from 'vue-router'

/** 首响应超时（毫秒）：发起请求后该时长内未收到响应头则判定服务端无响应，主动中断 */
const FIRST_RESPONSE_TIMEOUT = 60 * 1000
/** 流空闲超时（毫秒）：流式过程中该时长内未收到任何 chunk 则中断（与后端 5 分钟超时对齐） */
const STREAM_IDLE_TIMEOUT = 5 * 60 * 1000
/** 流式草稿持久化节流间隔（毫秒）：避免每个 Token 都完整序列化写 localStorage */
const DRAFT_PERSIST_INTERVAL = 1500
/** 流式草稿在本地保留的最长时长（7 天），超过后视为过期丢弃 */
const DRAFT_TTL_MS = 7 * 86400000
/** 草稿 localStorage 键前缀（按账号隔离，后缀为用户名） */
const DRAFT_KEY_PREFIX = 'chatai-stream-draft:'

/**
 * 流式回答生命周期状态：
 * idle=未开始，streaming=生成中，done=正常完成（收到有效结束信号），
 * stopped=用户主动停止，offline=网络中断，timeout=超时，failed=失败/流内错误
 * 约定：只有明确收到 [DONE] 或正常流结束才记为 done；连接结束但未收到有效完成信号按中断处理
 */
export const STREAM_STATUS = {
  IDLE: 'idle',
  STREAMING: 'streaming',
  DONE: 'done',
  STOPPED: 'stopped',
  OFFLINE: 'offline',
  TIMEOUT: 'timeout',
  FAILED: 'failed'
}

export function useStreamChat() {
  const router = useRouter()
  const isStreaming = ref(false)
  const thinkingContent = ref('')
  const answerContent = ref('')
  const thinkingTime = ref(null)
  const usage = ref(null)
  const error = ref(null)
  // 当前请求生命周期状态（见 STREAM_STATUS 注释）
  const status = ref(STREAM_STATUS.IDLE)

  let controller = null
  let buffer = ''
  let thinkingStartTime = null
  // 内部中断原因标记：'timeout'/'offline' 时 AbortError 不再视为用户主动中断，按错误展示
  let abortReason = null
  // 超时计时器（首响应 / 流空闲共用一个句柄，每次收到数据时重置）
  let idleTimer = null
  // 单次结算令牌：每次请求只允许 settle 一次，防止 onDone/onError/finally 与迟到回调重复写入
  let settled = false
  // 当前请求关联的会话与账号（草稿按键隔离），以及是否收到过有效完成信号
  let draftChatId = null
  let draftUsage = null
  let sawDoneSignal = false
  // 草稿节流持久化计时器
  let draftTimer = null

  // 结算思考用时：最小记 1 秒，避免思考很快时 round 出 0（falsy）导致
  // 历史消息渲染时误判为“正在思考”且持久化后刷新也无法恢复
  function finalizeThinkingTime() {
    if (thinkingStartTime && !thinkingTime.value) {
      thinkingTime.value = Math.max(1, Math.round((Date.now() - thinkingStartTime) / 1000))
    }
  }

  /** 当前账号的流式草稿存储键（按账号隔离，避免不同用户互相读取草稿） */
  function draftKey(chatId) {
    let username = 'anonymous'
    try { username = localStorage.getItem('username') || 'anonymous' } catch (e) { /* ignore */ }
    return `${DRAFT_KEY_PREFIX}${username}:${chatId}`
  }

  /**
   * 持久化当前流式草稿（节流调用）：正文/思考/用量快照，
   * 供断网、刷新或重新打开页面后恢复已显示的部分回答。
   * 存储容量不足或损坏时静默降级，不影响流式主流程。
   */
  function persistDraft() {
    if (!draftChatId) return
    try {
      const snapshot = {
        chatId: draftChatId,
        content: answerContent.value,
        reasoning_content: thinkingContent.value,
        thinkingTime: thinkingTime.value,
        usage: draftUsage,
        status: status.value,
        savedAt: Date.now()
      }
      localStorage.setItem(draftKey(draftChatId), JSON.stringify(snapshot))
    } catch (e) { /* 容量不足时放弃本次草稿写入，不打断流式输出 */ }
  }

  /** 调度一次节流草稿写入（收到 chunk 时调用，避免每个 Token 都序列化写盘） */
  function scheduleDraftPersist() {
    if (draftTimer) return
    draftTimer = setTimeout(() => {
      draftTimer = null
      persistDraft()
    }, DRAFT_PERSIST_INTERVAL)
  }

  /** 清除草稿持久化计时器（结束时调用，并做最后一次落盘） */
  function flushDraftPersist() {
    if (draftTimer) {
      clearTimeout(draftTimer)
      draftTimer = null
    }
    persistDraft()
  }

  /** 清除指定会话的流式草稿（正常完成或用户放弃后调用） */
  function clearDraft(chatId) {
    try {
      if (chatId) localStorage.removeItem(draftKey(chatId))
    } catch (e) { /* ignore */ }
  }

  /**
   * 读取指定会话的流式草稿（刷新/重开页面恢复用）。
   * 过期或损坏的草稿返回 null 并顺手清理。
   */
  function loadDraft(chatId) {
    try {
      const raw = localStorage.getItem(draftKey(chatId))
      if (!raw) return null
      const draft = JSON.parse(raw)
      if (!draft || draft.chatId !== chatId) return null
      if (Date.now() - Number(draft.savedAt || 0) > DRAFT_TTL_MS) {
        clearDraft(chatId)
        return null
      }
      return draft
    } catch (e) {
      // 损坏数据直接清理，避免反复解析失败
      clearDraft(chatId)
      return null
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
    settled = false
    sawDoneSignal = false
    draftChatId = null
    draftUsage = null
    status.value = STREAM_STATUS.IDLE
    clearIdleTimer()
    if (draftTimer) {
      clearTimeout(draftTimer)
      draftTimer = null
    }
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

  /**
   * 统一结算入口：保证每次请求只结算一次、消息只保存一次。
   * finalStatus 为终态，result 为最终回调载荷；后续迟到回调直接忽略。
   * 中断/失败时若已收到有效正文或思考内容，走 onDone 由调用方保留部分内容；
   * 仅在完全无有效内容时才走 onError 落纯错误气泡。
   */
  function settle(finalStatus, result, { onDone, onError }) {
    if (settled) return
    settled = true
    status.value = finalStatus
    // 终态以 settle 为准：result 在 settle 前构造，其中 status 字段仍是 streaming，必须覆盖
    if (result) result.status = finalStatus
    isStreaming.value = false
    clearIdleTimer()
    flushDraftPersist()
    window.removeEventListener('offline', onOfflineAbort)
    // 正常完成或用户明确停止后清除草稿；中断/失败保留草稿供恢复
    if (finalStatus === STREAM_STATUS.DONE) {
      clearDraft(draftChatId)
    }
    const hasContent = !!(result && ((result.content && result.content.trim()) ||
        (result.reasoning_content && result.reasoning_content.trim())))
    if (finalStatus === STREAM_STATUS.FAILED && result && result.error && !hasContent) {
      if (onError) onError(new Error(result.error))
      return
    }
    if (onDone) onDone(result)
  }

  /**
   * 组装结算载荷：保留已收到的正文、思考内容及可用用量数据，
   * 断网/超时/失败时不只留下错误气泡。
   */
  function buildResult(extra) {
    return {
      content: answerContent.value,
      reasoning_content: thinkingContent.value,
      thinkingTime: thinkingTime.value,
      usage: usage.value,
      status: status.value,
      ...extra
    }
  }

  /**
   * 发起流式请求。
   * @param requestBody 请求体（含 modelConfigId/messages/stream 等）
   * @param options.onUpdate 流式增量回调（正文/思考/用量快照）
   * @param options.onDone 终态回调（完成/用户停止/中断均走此，载荷含 status 区分）
   * @param options.onError 失败回调（请求级失败且无有效内容时）
   * @param options.chatId 关联会话ID（流式草稿持久化与恢复键）
   */
  async function send(requestBody, { onUpdate, onDone, onError, chatId } = {}) {
    reset()
    draftChatId = chatId || null
    // 断网前置检查：避免离线时发起注定失败的请求，直接给出友好提示
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      status.value = STREAM_STATUS.OFFLINE
      error.value = '当前网络已断开，请检查网络连接后重试'
      settle(STREAM_STATUS.OFFLINE, buildResult({ error: error.value, interrupted: true }), { onDone, onError })
      return
    }
    isStreaming.value = true
    status.value = STREAM_STATUS.STREAMING
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
        status.value = STREAM_STATUS.FAILED
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
          finalizeThinkingTime()
          // 连接结束但未收到有效完成信号且有未解释的错误：按中断处理而非标记完整
          if (!sawDoneSignal && error.value) {
            settle(STREAM_STATUS.FAILED, buildResult({ error: error.value, interrupted: true }), { onDone, onError })
            return
          }
          settle(STREAM_STATUS.DONE, buildResult({ error: error.value || undefined }), { onDone, onError })
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
          if (!payload) continue
          if (payload === '[DONE]') {
            // 收到服务端有效完成信号，允许流正常结算为 done
            sawDoneSignal = true
            continue
          }
          if (payload.startsWith('{')) {
            try {
              const json = JSON.parse(payload)
              if (json.error) {
                // 流内错误：记录但继续读完剩余内容，由结算统一处理
                error.value = json.error.message || '未知错误'
                continue
              }
              // 提取usage数据
              if (json.usage) {
                usage.value = json.usage
                draftUsage = json.usage
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

        if (updated) {
          if (onUpdate) {
            onUpdate({
              content: answerContent.value,
              reasoning_content: thinkingContent.value,
              thinkingTime: thinkingTime.value,
              usage: usage.value
            })
          }
          // 节流持久化草稿，支持断网/刷新后恢复
          scheduleDraftPersist()
        }

        return read()
      }

      await read()
    } catch (err) {
      if (err.name === 'AbortError') {
        finalizeThinkingTime()
        if (abortReason === 'timeout') {
          // 超时中断：保留已生成的正文/思考，明确标记为超时而非完成
          error.value = '响应超时，请稍后重试或更换模型'
          settle(STREAM_STATUS.TIMEOUT, buildResult({ error: error.value, interrupted: true }), { onDone, onError })
          return
        }
        if (abortReason === 'offline') {
          // 网络中断：保留已生成的正文/思考，明确标记为网络中断
          error.value = '网络连接已断开，回答已中断，恢复网络后可重新发送'
          settle(STREAM_STATUS.OFFLINE, buildResult({ error: error.value, interrupted: true }), { onDone, onError })
          return
        }
        // 用户主动停止：保留已生成内容，标记为 stopped
        settle(STREAM_STATUS.STOPPED, buildResult({ interrupted: true }), { onDone, onError })
        return
      }
      finalizeThinkingTime()
      // fetch 网络级失败（TypeError）统一为友好提示，避免暴露 "Failed to fetch" 原文
      if (err instanceof TypeError) {
        error.value = navigator.onLine === false ? '网络连接已断开，请检查网络后重试' : '无法连接到服务器，请稍后重试'
        settle(navigator.onLine === false ? STREAM_STATUS.OFFLINE : STREAM_STATUS.FAILED,
          buildResult({ error: error.value, interrupted: true }), { onDone, onError })
        return
      }
      error.value = err.message
      settle(STREAM_STATUS.FAILED, buildResult({ error: err.message, interrupted: true }), { onDone, onError })
    } finally {
      // 兜底释放：settle 内部已做清理，这里仅保证计时器与监听不泄漏
      clearIdleTimer()
      abortReason = null
      window.removeEventListener('offline', onOfflineAbort)
    }
  }

  function stop() {
    if (controller) {
      abortReason = null
      controller.abort()
      controller = null
    }
  }

  return {
    isStreaming, thinkingContent, answerContent, thinkingTime, usage, error, status,
    send, stop, reset, loadDraft, clearDraft
  }
}
