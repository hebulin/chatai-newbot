import { nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { generateChatTitle } from '@/api/chat'

/**
 * 管理回答重新生成、版本切换、编辑重发与会话分支操作。
 */
export function useChatMessageActions({
  chatStore,
  modelsStore,
  streamChat,
  scrollFollow,
  hiddenCount,
  isDeepThinking,
  startStream,
  t
}) {
  /**
   * 首次问答完成后，在用户未手动命名时生成会话标题。
   */
  async function maybeGenerateTitle(chatId) {
    const messages = chatStore.chats[chatId] || []
    const userMessages = messages.filter(message => message.role === 'user')
    const assistantMessages = messages.filter(message =>
      message.role === 'assistant' && message.content && !message.interrupted && !message.isError
    )
    if (userMessages.length !== 1 || assistantMessages.length < 1) return
    const meta = chatStore.chatMeta[chatId] || {}
    if (meta.title?.trim()) return
    try {
      const result = await generateChatTitle(
        modelsStore.currentModelId,
        userMessages[0].content,
        assistantMessages[0].content
      )
      if (result?.success && result.title) chatStore.setAutoTitleIfEmpty(chatId, result.title)
    } catch { /* 标题生成失败时保留默认标题 */ }
  }

  /**
   * 为指定回答生成新版本，保留原回答供后续切换。
   */
  async function handleRegenerate(displayIndex) {
    const messageIndex = displayIndex + hiddenCount.value
    if (streamChat.isStreaming.value) {
      ElMessage.warning(t('chat.waitAnswer'))
      return
    }
    if (!modelsStore.currentModelId) {
      ElMessage.warning(t('chat.pickModel'))
      return
    }
    const chatId = chatStore.currentChatId
    const target = (chatStore.chats[chatId] || [])[messageIndex]
    if (!target || target.role !== 'assistant') return
    try {
      await ElMessageBox.confirm(t('chat.regenerateConfirm'), t('chat.regenerateTitle'), {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      })
    } catch { return }
    chatStore.suspendSync()
    try {
      nextTick(() => scrollFollow.scrollToBottomImmediate())
      await startStream(chatId, isDeepThinking.value, { regenTarget: { chatId, msgIdx: messageIndex } })
    } finally {
      chatStore.resumeSync()
    }
  }

  /**
   * 定位最后一条中断回答并沿用版本历史流程重新生成。
   */
  async function handleRegenerateInterrupted() {
    const chatId = chatStore.currentChatId
    if (!chatId) return
    const messages = chatStore.chats[chatId] || []
    let index = messages.length - 1
    while (index >= 0) {
      const message = messages[index]
      if (message.role === 'assistant' && message.interrupted) break
      if (message.role === 'assistant' && message.isError) { index--; continue }
      return
    }
    if (index >= 0) await handleRegenerate(index - hiddenCount.value)
  }

  /**
   * 切换回答版本；存在后续对话时允许只查看或从该版本建立分支。
   */
  async function handleSelectVersion({ absIdx, versionId }) {
    const chatId = chatStore.currentChatId
    if (!chatId) return
    const messages = chatStore.chats[chatId] || []
    if (!messages[absIdx]) return
    if (absIdx >= messages.length - 1) {
      chatStore.selectMessageVersion(chatId, absIdx, versionId)
      return
    }
    let action = 'cancel'
    try {
      await ElMessageBox.confirm(t('chat.versionSwitchConfirm'), t('chat.versionSwitchTitle'), {
        confirmButtonText: t('chat.versionSwitchBranch'),
        cancelButtonText: t('chat.versionSwitchViewOnly'),
        distinguishCancelAndClose: true,
        type: 'warning'
      })
      action = 'branch'
    } catch (error) {
      if (error === 'cancel') action = 'view'
    }
    if (action === 'branch') {
      const branchId = chatStore.createBranch(chatId, absIdx)
      if (branchId) {
        chatStore.selectMessageVersion(branchId, absIdx, versionId)
        nextTick(() => scrollFollow.scrollToBottomImmediate())
      }
    } else if (action === 'view') {
      chatStore.selectMessageVersion(chatId, absIdx, versionId)
    }
  }

  /**
   * 在原会话编辑用户消息，复用对应回答的版本历史，不自动创建分支。
   */
  async function handleEditResend(displayIndex) {
    const messageIndex = displayIndex + hiddenCount.value
    if (streamChat.isStreaming.value) {
      ElMessage.warning(t('chat.waitAnswer'))
      return
    }
    if (!modelsStore.currentModelId) {
      ElMessage.warning(t('chat.pickModel'))
      return
    }
    const sourceChatId = chatStore.currentChatId
    const message = (chatStore.chats[sourceChatId] || [])[messageIndex]
    if (!message || message.role !== 'user') return
    let newText
    try {
      const result = await ElMessageBox.prompt(t('chat.editResendPrompt'), t('chat.editResendTitle'), {
        inputType: 'textarea',
        inputValue: message.content,
        confirmButtonText: t('chat.resend'),
        cancelButtonText: t('common.cancel'),
        inputValidator: value => value?.trim() ? true : t('chat.notEmpty')
      })
      newText = (result.value || '').trim()
    } catch { return }

    // 弹窗等待期间可能切换会话、同步替换消息或开始另一轮请求，不能用旧下标修改新状态。
    if (chatStore.currentChatId !== sourceChatId || streamChat.isStreaming.value
      || chatStore.chats[sourceChatId]?.[messageIndex] !== message || !newText) return
    const messages = chatStore.chats[sourceChatId]
    const nextMessage = messages[messageIndex + 1]
    const replyIndex = nextMessage?.role === 'assistant' ? messageIndex + 1 : -1
    chatStore.suspendSync()
    try {
      if (!chatStore.editUserMessage(sourceChatId, messageIndex, newText)) return
      nextTick(() => scrollFollow.scrollToBottomImmediate())
      await startStream(sourceChatId, isDeepThinking.value, replyIndex >= 0
        ? { regenTarget: { chatId: sourceChatId, msgIdx: replyIndex } }
        : { contextEnd: messageIndex + 1, replyInsertIndex: messageIndex + 1 })
    } finally {
      chatStore.resumeSync()
    }
  }

  /**
   * 从指定消息位置复制上下文，创建可独立继续的会话分支。
   */
  async function handleCreateBranch(displayIndex) {
    try {
      await ElMessageBox.confirm(t('chat.branchConfirm'), t('chat.branchTitle'), {
        confirmButtonText: t('common.confirm'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      })
    } catch { return }
    const branchId = chatStore.createBranch(chatStore.currentChatId, displayIndex + hiddenCount.value)
    if (branchId) {
      nextTick(() => scrollFollow.scrollToBottomImmediate())
      ElMessage.success('已创建会话分支，原会话内容保持不变')
    }
  }

  return {
    maybeGenerateTitle,
    handleRegenerate,
    handleRegenerateInterrupted,
    handleSelectVersion,
    handleEditResend,
    handleCreateBranch
  }
}
