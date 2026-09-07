import { computed, nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { estimateChatTokens, formatChatTime } from './chatMessageUtils'

/** 管理上下文占用、流式草稿恢复、继续生成与清除上下文。 */
export function useChatContext({ chatStore, modelsStore, streamChat, scrollFollow, isDeepThinking, startStream, t }) {
  // 当前会话上下文占用估算信息（超出 85% 容量时警示色）
  const contextUsageInfo = computed(() => {
    const msgs = chatStore.currentMessages
    if (!msgs.length || !chatStore.isChatHistoryLoaded) return null
    const window_ = modelsStore.currentModelContextWindow
    let used = 0
    // 与后端一致：仅计最后一个“清除上下文”分隔线之后的消息（倒序累计，遇分隔线停止）
    for (let i = msgs.length - 1; i >= 0; i--) {
      const m = msgs[i]
      if (m.role === 'divider') break
      if (m.role !== 'user' && m.role !== 'assistant') continue
      used += 4 + estimateChatTokens(m.content)
      if (m.images) used += m.images.length * 1100
      if (m.attachments) used += m.attachments.length * 500
    }
    const ratio = used / window_
    const fmtK = (n) => n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n)
    return {
      ratio,
      text: t('chat.contextUsage', { used: fmtK(used), total: fmtK(window_) })
    }
  })

  // ===== 流式草稿恢复与“继续生成”入口 =====
  // 刷新/重新打开页面后，若当前会话存在未结算的流式草稿，恢复为一条已标记中断的回答消息；
  // 中断的回答提供“重新生成”与“基于已生成内容继续”两个明确操作（继续生成是新的请求，不伪称断点续传）
  const draftRestoreTip = ref(null)

  // 检查并恢复当前会话的流式草稿（切换会话/刷新后调用）
  function restoreStreamDraftIfAny() {
    draftRestoreTip.value = null
    const chatId = chatStore.currentChatId
    if (!chatId) return
    const draft = streamChat.loadDraft(chatId)
    if (!draft) return
    const hasContent = !!(draft.content && String(draft.content).trim())
    const hasReasoning = !!(draft.reasoning_content && String(draft.reasoning_content).trim())
    if (!hasContent && !hasReasoning) {
      streamChat.clearDraft(chatId)
      return
    }
    const msgs = chatStore.chats[chatId] || []
    const last = msgs[msgs.length - 1]
    // 幂等：若最后一条已是该草稿对应的中断消息（内容一致），不重复恢复
    if (last && last.role === 'assistant' && last.interrupted && last.content === draft.content) {
      streamChat.clearDraft(chatId)
      return
    }
    const restored = {
      role: 'assistant',
      content: draft.content || t('chat.answerInterrupted'),
      reasoning_content: draft.reasoning_content || undefined,
      thinkingTime: draft.thinkingTime || undefined,
      time: formatChatTime(),
      interrupted: true,
      status: draft.status && draft.status !== 'streaming' ? draft.status : 'offline',
      modelName: modelsStore.currentModelName
    }
    if (draft.usage) {
      restored.promptTokens = draft.usage.prompt_tokens || 0
      restored.completionTokens = draft.usage.completion_tokens || 0
      restored.reasoningTokens = draft.usage.completion_tokens_details?.reasoning_tokens || 0
      restored.cachedTokens = draft.usage.prompt_tokens_details?.cached_tokens || 0
    }
    chatStore.addMessage(chatId, restored)
    streamChat.clearDraft(chatId)
    draftRestoreTip.value = { chatId }
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  }

  // 当前会话最后一条 assistant 消息是否为中断状态（决定“继续生成”操作条是否展示）
  const lastInterruptedMsg = computed(() => {
    const msgs = chatStore.currentMessages
    if (!msgs.length || streamChat.isStreaming.value) return null
    const last = msgs[msgs.length - 1]
    return (last && last.role === 'assistant' && last.interrupted) ? last : null
  })

  // 基于已生成内容继续：把中断的部分回答保留，追加一条“请继续”用户消息发起新请求。
  // 明确这是新请求（上游流无法断点续传），不是恢复原来的流。
  async function handleContinueGeneration() {
    const chatId = chatStore.currentChatId
    if (!chatId || streamChat.isStreaming.value) return
    const continueText = t('chat.continuePrompt')
    chatStore.addMessage(chatId, { role: 'user', content: continueText, time: formatChatTime() })
    chatStore.suspendSync()
    try {
      nextTick(() => scrollFollow.scrollToBottomImmediate())
      await startStream(chatId, isDeepThinking.value)
    } finally {
      chatStore.resumeSync()
    }
  }

  // 清除上下文：二次确认后向当前会话插入一条分隔线，后续对话不再携带此前历史
  async function handleClearContext() {
    if (streamChat.isStreaming.value) {
      ElMessage.warning(t('chat.waitAnswer'))
      return
    }
    const chatId = chatStore.currentChatId
    const msgs = chatStore.chats[chatId] || []
    if (msgs.length === 0) {
      ElMessage.info(t('chat.clearCtxEmpty'))
      return
    }
    // 避免连续插入多条分隔线
    if (msgs[msgs.length - 1].role === 'divider') {
      ElMessage.info(t('chat.ctxCleared'))
      return
    }
    try {
      await ElMessageBox.confirm(t('chat.clearCtxConfirm'), t('chat.clearCtxTitle'), {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      })
    } catch { return }
    chatStore.addMessage(chatId, { role: 'divider', time: null })
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  }


  return { contextUsageInfo, draftRestoreTip, lastInterruptedMsg, restoreStreamDraftIfAny,
    handleContinueGeneration, handleClearContext }
}
