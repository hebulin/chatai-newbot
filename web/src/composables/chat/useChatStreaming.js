import { nextTick, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { formatChatTime } from './chatMessageUtils'

/**
 * 管理聊天页的流式请求、结算、停止与发送前同步状态。
 */
export function useChatStreaming({ chatStore, modelsStore, streamChat, scrollFollow, t, onGenerateTitle }) {
  const isDeepThinking = ref(false)
  const isWebSearch = ref(false)
  const streamingMsg = ref(null)
  const syncTipVisible = ref(false)
  const regenStreamingIdx = ref(-1)

  /**
   * 发送一条用户消息并在同步当前会话后发起流式回答。
   */
  async function handleSend({ text, images, attachments, deepThinking, webSearch }) {
    if (streamChat.isStreaming.value) {
      ElMessage.warning(t('chat.stillStreaming'))
      return
    }
    if (!text && (!images || images.length === 0) && (!attachments || attachments.length === 0)) return
    if (!modelsStore.currentModelId) {
      ElMessage.warning(t('chat.pickModel'))
      return
    }

    isDeepThinking.value = deepThinking
    isWebSearch.value = !!webSearch
    const chatId = chatStore.currentChatId
    chatStore.suspendSync()
    try {
      const userMsg = {
        role: 'user',
        content: text || (images?.length ? t('chat.imgPlaceholder') : t('chat.attachPlaceholder')),
        time: formatChatTime()
      }
      if (images?.length) userMsg.images = images.slice()
      if (attachments?.length) userMsg.attachments = attachments.slice()
      chatStore.addMessage(chatId, userMsg)
      nextTick(() => scrollFollow.scrollToBottomImmediate())
      await syncCurrentChatBeforeSend(chatId, 1)
      await startStream(chatId, deepThinking)
    } finally {
      chatStore.resumeSync()
    }
  }

  /**
   * 发送前同步当前会话，超过三秒才展示等待提示。
   */
  async function syncCurrentChatBeforeSend(chatId, pendingCount) {
    const tipTimer = setTimeout(() => { syncTipVisible.value = true }, 3000)
    try {
      const merged = await chatStore.syncCurrentChatFromServer(chatId, pendingCount)
      if (merged) {
        await nextTick()
        scrollFollow.scrollToBottomImmediate()
      }
    } catch (error) {
      console.error('当前会话同步失败:', error)
    } finally {
      clearTimeout(tipTimer)
      syncTipVisible.value = false
    }
  }

  /**
   * 基于当前上下文发起流式请求；重新生成时将结果写入目标回答的版本历史。
   */
  async function startStream(chatId, deepThinking, options = {}) {
    const regenTarget = options.regenTarget || null
    const source = chatStore.chats[chatId] || []
    // 重生成仅使用目标回答之前的上下文，后续对话保留在会话中但不送入本次请求。
    const contextEnd = regenTarget ? regenTarget.msgIdx : (options.contextEnd ?? source.length)
    let startIdx = 0
    for (let i = contextEnd - 1; i >= 0; i--) {
      if (source[i].role === 'divider') { startIdx = i + 1; break }
    }
    const messages = source.slice(startIdx, contextEnd)
      .filter(message => {
        return message.role === 'user' || (message.role === 'assistant' && !message.isError && message.content?.trim())
      })
      .map(message => {
        const normalized = { role: message.role, content: message.content }
        if (message.role === 'user') {
          if (message.images?.length) normalized.images = message.images
          if (message.attachments?.length) normalized.attachments = message.attachments
        }
        return normalized
      })

    const requestBody = {
      modelConfigId: modelsStore.currentModelId,
      chatId,
      messages,
      stream: true,
      deepThinking,
      webSearch: isWebSearch.value,
      promptPresetId: (chatStore.chatMeta[chatId] || {}).promptPresetId || '',
      temperature: 0.7
    }
    streamingMsg.value = {
      role: 'assistant', content: '', reasoning_content: '', time: null, modelName: modelsStore.currentModelName
    }
    regenStreamingIdx.value = regenTarget ? regenTarget.msgIdx : -1

    await streamChat.send(requestBody, {
      chatId,
      onUpdate: data => updateStreamingMessage(data),
      onDone: data => settleStreamingMessage(chatId, data, regenTarget, options.replyInsertIndex),
      onError: error => settleRequestError(chatId, error, regenTarget, options.replyInsertIndex)
    })
  }

  /**
   * 应用单次流式增量并跟随滚动。
   */
  function updateStreamingMessage(data) {
    streamingMsg.value = {
      role: 'assistant',
      content: data.content,
      reasoning_content: data.reasoning_content,
      thinkingTime: data.thinkingTime,
      time: formatChatTime(),
      modelName: modelsStore.currentModelName
    }
    scrollFollow.syncScrollToBottom()
  }

  /**
   * 结算一次已建立连接的流式请求，确保消息仅落库一次。
   */
  function settleStreamingMessage(chatId, data, regenTarget, replyInsertIndex) {
    const hasContent = !!data.content?.trim()
    const hasReasoning = !!data.reasoning_content?.trim()
    const interrupted = data.status && data.status !== 'done'
    if (regenTarget) {
      settleRegeneratedVersion(data, regenTarget, hasContent, hasReasoning, interrupted)
      return
    }
    if (!hasContent && !hasReasoning && data.error) {
      chatStore.addMessage(chatId, makeErrorMsg(data.error), replyInsertIndex)
      finishStreamingUi()
      return
    }
    const message = createAssistantMessage(chatId, data, hasContent, interrupted, replyInsertIndex)
    chatStore.addMessage(chatId, message, replyInsertIndex)
    if (data.error && interrupted) {
      chatStore.addMessage(chatId, makeErrorMsg(data.error), Number.isInteger(replyInsertIndex) ? replyInsertIndex + 1 : undefined)
    }
    finishStreamingUi()
    onGenerateTitle?.(chatId)
  }

  /**
   * 将重新生成结果追加到目标回答的版本历史。
   */
  function settleRegeneratedVersion(data, target, hasContent, hasReasoning, interrupted) {
    if (hasContent || hasReasoning) {
      chatStore.addMessageVersion(target.chatId, target.msgIdx, {
        content: hasContent ? data.content : '',
        reasoning_content: data.reasoning_content || undefined,
        thinkingTime: data.thinkingTime,
        modelName: modelsStore.currentModelName,
        time: formatChatTime(),
        status: data.status || 'done',
        usage: normalizeUsage(data.usage)
      }, !interrupted)
    }
    if (interrupted) ElMessage.warning(data.error || t('chat.answerInterrupted'))
    finishStreamingUi()
  }

  /**
   * 创建普通回答消息并计算当前轮次的增量输入 Token。
   */
  function createAssistantMessage(chatId, data, hasContent, interrupted, replyInsertIndex) {
    const message = {
      role: 'assistant',
      content: hasContent ? data.content : (interrupted ? t('chat.answerInterrupted') : t('chat.noAnswer')),
      reasoning_content: data.reasoning_content || undefined,
      time: formatChatTime(),
      interrupted: interrupted || undefined,
      status: interrupted ? data.status : undefined,
      modelName: modelsStore.currentModelName,
      thinkingTime: data.thinkingTime
    }
    if (!data.usage) return message
    const usage = normalizeUsage(data.usage)
    Object.assign(message, usage)
    const messages = (chatStore.chats[chatId] || []).slice(0, replyInsertIndex)
    let turnInputTokens = usage.promptTokens
    for (let index = messages.length - 1; index >= 0; index--) {
      if (messages[index].role === 'assistant' && messages[index].promptTokens) {
        turnInputTokens = usage.promptTokens - messages[index].promptTokens
        break
      }
    }
    if (turnInputTokens < 0) turnInputTokens = usage.promptTokens
    message.turnInputTokens = turnInputTokens
    for (let index = messages.length - 1; index >= 0; index--) {
      if (messages[index].role === 'user') {
        messages[index].promptTokens = turnInputTokens
        break
      }
    }
    return message
  }

  /**
   * 将上游 usage 字段归一化为本地消息字段。
   */
  function normalizeUsage(usage) {
    if (!usage) return undefined
    return {
      promptTokens: usage.prompt_tokens || 0,
      completionTokens: usage.completion_tokens || 0,
      reasoningTokens: usage.completion_tokens_details?.reasoning_tokens || 0,
      cachedTokens: usage.prompt_tokens_details?.cached_tokens || 0
    }
  }

  /**
   * 结算尚未建立有效流的请求级错误。
   */
  function settleRequestError(chatId, error, regenTarget, replyInsertIndex) {
    if (regenTarget) ElMessage.error(error.message || t('chat.unknownError'))
    else chatStore.addMessage(chatId, makeErrorMsg(error.message), replyInsertIndex)
    finishStreamingUi()
  }

  /**
   * 创建统一样式的错误气泡消息。
   */
  function makeErrorMsg(text) {
    return {
      role: 'assistant',
      content: text || t('chat.unknownError'),
      isError: true,
      time: formatChatTime(),
      modelName: modelsStore.currentModelName
    }
  }

  /**
   * 清理流式 UI 状态并更新滚动导航。
   */
  function finishStreamingUi() {
    streamingMsg.value = null
    regenStreamingIdx.value = -1
    nextTick(() => {
      scrollFollow.syncScrollToBottom()
      scrollFollow.updateNavButtons()
    })
  }

  /**
   * 停止当前流式请求。
   */
  function handleStop() {
    streamChat.stop()
  }

  /**
   * 同步输入框的深度思考开关，供重生成与继续生成复用。
   */
  function handleThinkingChange(value) {
    isDeepThinking.value = !!value
  }

  return {
    isDeepThinking,
    isWebSearch,
    streamingMsg,
    syncTipVisible,
    regenStreamingIdx,
    handleSend,
    startStream,
    handleStop,
    handleThinkingChange
  }
}
