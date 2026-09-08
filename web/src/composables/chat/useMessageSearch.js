import { computed, nextTick, ref, watch } from 'vue'

/**
 * 管理当前会话内的消息搜索、游标循环和定位行为。
 * @param {{ messages: import('vue').Ref<Array>, conversationId: import('vue').Ref<string|null>, onJump: Function }} options 搜索输入
 */
export function useMessageSearch({ messages, conversationId, onJump }) {
  const msgSearchOpen = ref(false)
  const msgSearchKeyword = ref('')
  const msgSearchCursor = ref(-1)
  const msgSearchInputRef = ref(null)

  const msgSearchMatches = computed(() => {
    const keyword = msgSearchKeyword.value.trim().toLowerCase()
    if (!keyword) return []
    const matches = []
    messages.value.forEach((message, index) => {
      if (message.role !== 'user' && message.role !== 'assistant') return
      if (String(message.content || '').toLowerCase().includes(keyword)) matches.push(index)
    })
    return matches
  })

  const msgSearchInfo = computed(() => {
    if (!msgSearchKeyword.value.trim()) return ''
    if (!msgSearchMatches.value.length) return '0/0'
    return `${msgSearchCursor.value + 1}/${msgSearchMatches.value.length}`
  })

  const activeMsgSearchIndex = computed(() => {
    if (msgSearchCursor.value < 0) return -1
    return msgSearchMatches.value[msgSearchCursor.value] ?? -1
  })

  /** 打开搜索栏并聚焦输入框。 */
  function openMsgSearch() {
    msgSearchOpen.value = true
    nextTick(() => msgSearchInputRef.value?.focus())
  }

  /** 关闭搜索栏并清空当前查询。 */
  function closeMsgSearch() {
    msgSearchOpen.value = false
    msgSearchKeyword.value = ''
    msgSearchCursor.value = -1
  }

  /** 跳转到绝对消息下标，并将当前命中滚动到视区。 */
  async function jumpToMatchIndex(messageIndex) {
    await onJump(messageIndex)
    await nextTick()
    document.querySelector(`#msg-anchor-${messageIndex} .msg-search-match-active`)?.scrollIntoView({
      behavior: 'smooth',
      block: 'center',
      inline: 'nearest'
    })
  }

  /** 按给定游标循环跳转到匹配消息。 */
  function moveCursor(cursor) {
    const matches = msgSearchMatches.value
    if (!matches.length) return
    msgSearchCursor.value = (cursor + matches.length) % matches.length
    jumpToMatchIndex(matches[msgSearchCursor.value])
  }

  /** 跳转到下一条匹配消息。 */
  function nextMsgMatch() {
    moveCursor(msgSearchCursor.value + 1)
  }

  /** 跳转到上一条匹配消息。 */
  function prevMsgMatch() {
    moveCursor(msgSearchCursor.value - 1)
  }

  watch(msgSearchKeyword, () => {
    if (!msgSearchMatches.value.length) {
      msgSearchCursor.value = -1
      return
    }
    msgSearchCursor.value = 0
    jumpToMatchIndex(msgSearchMatches.value[0])
  })
  watch(conversationId, closeMsgSearch)

  return {
    msgSearchOpen,
    msgSearchKeyword,
    msgSearchInputRef,
    msgSearchInfo,
    activeMsgSearchIndex,
    openMsgSearch,
    closeMsgSearch,
    nextMsgMatch,
    prevMsgMatch
  }
}
