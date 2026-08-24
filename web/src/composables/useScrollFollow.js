import { ref, onUnmounted } from 'vue'

export function useScrollFollow(containerRef) {
  const autoFollowEnabled = ref(true)
  const showScrollToBottom = ref(false)

  let followBeforeHidden = true
  let pageVisible = true
  let touchPaused = false
  let lastScrollTime = 0
  let rafScheduled = false
  let pendingScroll = false
  let programmaticScroll = false
  let contentObserver = null
  let lastObservedHeight = 0

  const MIN_INTERVAL = 50 // 1000/20 = 50ms
  const NEAR_BOTTOM_THRESHOLD = 80

  function init() {
    const container = containerRef.value
    if (!container) return
    autoFollowEnabled.value = true
    touchPaused = false
    pageVisible = !document.hidden
    lastObservedHeight = container.scrollHeight
    bindEvents()
    attachContentObserver()
    updateNavButtons()
  }

  function bindEvents() {
    const container = containerRef.value
    if (!container) return
    container.addEventListener('scroll', onContainerScroll, { passive: true })
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('blur', onWindowBlur)
    window.addEventListener('focus', onWindowFocus)
    container.addEventListener('touchstart', onTouchStart, { passive: true })
  }

  function unbindEvents() {
    const container = containerRef.value
    if (!container) return
    container.removeEventListener('scroll', onContainerScroll)
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('blur', onWindowBlur)
    window.removeEventListener('focus', onWindowFocus)
    container.removeEventListener('touchstart', onTouchStart)
    if (contentObserver) {
      contentObserver.disconnect()
      contentObserver = null
    }
  }

  function attachContentObserver() {
    const container = containerRef.value
    if (!container || contentObserver) return
    contentObserver = new MutationObserver(onContentMutation)
    contentObserver.observe(container, { childList: true, subtree: true, attributes: true })
    lastObservedHeight = container.scrollHeight
  }

  function onContentMutation() {
    const container = containerRef.value
    if (!container) return
    const currentHeight = container.scrollHeight
    if (currentHeight !== lastObservedHeight) {
      lastObservedHeight = currentHeight
      syncScrollToBottom()
    }
  }

  function onContainerScroll() {
    if (programmaticScroll) return
    syncFollowStateFromScroll()
    updateNavButtons()
  }

  function syncFollowStateFromScroll() {
    if (touchPaused) {
      if (isNearBottom()) {
        touchPaused = false
        autoFollowEnabled.value = true
      }
      return
    }
    if (isNearBottom()) {
      autoFollowEnabled.value = true
    } else {
      autoFollowEnabled.value = false
    }
  }

  function requestScrollToBottom() {
    if (!pageVisible) return
    if (!autoFollowEnabled.value || touchPaused) return
    scheduleRaf()
  }

  function scheduleRaf() {
    pendingScroll = true
    if (rafScheduled) return
    rafScheduled = true
    requestAnimationFrame(processPendingScroll)
  }

  function processPendingScroll() {
    rafScheduled = false
    if (!pendingScroll) return
    const now = Date.now()
    if (now - lastScrollTime < MIN_INTERVAL) {
      pendingScroll = true
      rafScheduled = true
      requestAnimationFrame(processPendingScroll)
      return
    }
    pendingScroll = false
    doScrollNow()
  }

  function doScrollNow() {
    const container = containerRef.value
    if (!container) return
    if (!pageVisible || !autoFollowEnabled.value || touchPaused) return
    programmaticScroll = true
    const prevBehavior = container.style.scrollBehavior
    container.style.scrollBehavior = 'auto'
    container.scrollTop = container.scrollHeight
    lastScrollTime = Date.now()
    container.style.scrollBehavior = prevBehavior
    requestAnimationFrame(() => { programmaticScroll = false })
  }

  function syncScrollToBottom() {
    const container = containerRef.value
    if (!container) return
    if (!pageVisible || !autoFollowEnabled.value || touchPaused) return
    programmaticScroll = true
    const prevBehavior = container.style.scrollBehavior
    container.style.scrollBehavior = 'auto'
    container.scrollTop = container.scrollHeight
    lastScrollTime = Date.now()
    container.style.scrollBehavior = prevBehavior
    requestAnimationFrame(() => { programmaticScroll = false })
  }

  function scrollToBottomImmediate() {
    const container = containerRef.value
    if (!container) return
    autoFollowEnabled.value = true
    touchPaused = false
    pendingScroll = false
    programmaticScroll = true
    const prevBehavior = container.style.scrollBehavior
    container.style.scrollBehavior = 'auto'
    container.scrollTop = container.scrollHeight
    lastScrollTime = Date.now()
    lastObservedHeight = container.scrollHeight
    container.style.scrollBehavior = prevBehavior
    requestAnimationFrame(() => { programmaticScroll = false })
    updateNavButtons()
  }

  function userScrollToBottom() {
    const container = containerRef.value
    if (!container) return
    autoFollowEnabled.value = true
    touchPaused = false
    pendingScroll = false
    programmaticScroll = true
    container.scrollTo({ top: container.scrollHeight, behavior: 'smooth' })
    lastScrollTime = Date.now()
    lastObservedHeight = container.scrollHeight
    setTimeout(() => { programmaticScroll = false }, 500)
    updateNavButtons()
  }

  function userScrollToTop() {
    const container = containerRef.value
    if (!container) return
    autoFollowEnabled.value = false
    programmaticScroll = true
    container.scrollTo({ top: 0, behavior: 'smooth' })
    setTimeout(() => { programmaticScroll = false }, 500)
    updateNavButtons()
  }

  function onVisibilityChange() {
    if (document.hidden) {
      pageVisible = false
      followBeforeHidden = autoFollowEnabled.value
    } else {
      pageVisible = true
      if (followBeforeHidden && !touchPaused) {
        autoFollowEnabled.value = true
        doScrollNow()
      }
    }
  }

  function onWindowBlur() {
    pageVisible = false
    followBeforeHidden = autoFollowEnabled.value
  }

  function onWindowFocus() {
    pageVisible = true
    if (followBeforeHidden && !touchPaused) {
      autoFollowEnabled.value = true
      doScrollNow()
    }
  }

  function onTouchStart() {
    touchPaused = true
    autoFollowEnabled.value = false
  }

  function updateNavButtons() {
    const container = containerRef.value
    if (!container) return
    const scrollTop = container.scrollTop
    const scrollHeight = container.scrollHeight
    const clientHeight = container.clientHeight
    const isAtBottom = scrollHeight - scrollTop - clientHeight <= 5
    showScrollToBottom.value = !isAtBottom
  }

  function isNearBottom() {
    const container = containerRef.value
    if (!container) return true
    return container.scrollHeight - container.scrollTop - container.clientHeight < NEAR_BOTTOM_THRESHOLD
  }

  function reset() {
    pendingScroll = false
    rafScheduled = false
    const container = containerRef.value
    if (container) lastObservedHeight = container.scrollHeight
  }

  onUnmounted(() => {
    unbindEvents()
  })

  return {
    autoFollowEnabled, showScrollToBottom,
    init, reset, requestScrollToBottom, syncScrollToBottom,
    scrollToBottomImmediate, userScrollToBottom, userScrollToTop,
    updateNavButtons, unbindEvents
  }
}
