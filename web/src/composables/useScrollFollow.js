import { ref, onUnmounted } from 'vue'

export function useScrollFollow(containerRef, options = {}) {
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
  let boundContainer = null

  const MIN_INTERVAL = 50 // 1000/20 = 50ms
  const configuredThreshold = Number(options.nearBottomThreshold)
  const NEAR_BOTTOM_THRESHOLD = Number.isFinite(configuredThreshold) ? Math.max(0, configuredThreshold) : 80

  // 获取当前已绑定容器；初始化前回退到传入 ref，兼容既有调用顺序
  function getContainer() {
    return boundContainer || containerRef.value
  }

  // 初始化滚动跟随并绑定当前 ref；动态容器替换时先完整解绑旧节点
  function init() {
    const container = containerRef.value
    if (!container) return
    if (boundContainer) unbindEvents()
    boundContainer = container
    autoFollowEnabled.value = true
    touchPaused = false
    pageVisible = !document.hidden
    lastObservedHeight = container.scrollHeight
    bindEvents()
    attachContentObserver()
    updateNavButtons()
  }

  // 绑定滚动、滚轮、触摸与页面可见性事件
  function bindEvents() {
    const container = getContainer()
    if (!container) return
    container.addEventListener('scroll', onContainerScroll, { passive: true })
    container.addEventListener('wheel', onContainerWheel, { passive: true })
    document.addEventListener('visibilitychange', onVisibilityChange)
    window.addEventListener('blur', onWindowBlur)
    window.addEventListener('focus', onWindowFocus)
    container.addEventListener('touchstart', onTouchStart, { passive: true })
  }

  // 解绑当前真实容器及全局事件，并终止尚未执行的跟随任务
  function unbindEvents() {
    const container = boundContainer
    if (container) {
      container.removeEventListener('scroll', onContainerScroll)
      container.removeEventListener('wheel', onContainerWheel)
      container.removeEventListener('touchstart', onTouchStart)
    }
    document.removeEventListener('visibilitychange', onVisibilityChange)
    window.removeEventListener('blur', onWindowBlur)
    window.removeEventListener('focus', onWindowFocus)
    if (contentObserver) {
      contentObserver.disconnect()
      contentObserver = null
    }
    pendingScroll = false
    rafScheduled = false
    programmaticScroll = false
    boundContainer = null
  }

  // 监听容器内部 DOM 变化，覆盖 Markdown 后处理等异步高度变化
  function attachContentObserver() {
    const container = getContainer()
    if (!container || contentObserver) return
    contentObserver = new MutationObserver(onContentMutation)
    contentObserver.observe(container, { childList: true, subtree: true, attributes: true })
    lastObservedHeight = container.scrollHeight
  }

  // 内容高度变化时按当前跟随状态同步到底部
  function onContentMutation() {
    const container = getContainer()
    if (!container) return
    const currentHeight = container.scrollHeight
    if (currentHeight !== lastObservedHeight) {
      lastObservedHeight = currentHeight
      syncScrollToBottom()
    }
  }

  // 处理容器滚动并刷新跟随状态与回到底部按钮
  function onContainerScroll() {
    if (programmaticScroll) return
    syncFollowStateFromScroll()
    updateNavButtons()
  }

  // 滚轮向上时先于 scroll 事件暂停跟随，避免高频流式更新在同一帧抢回底部
  function onContainerWheel(event) {
    if (event.deltaY >= 0) return
    autoFollowEnabled.value = false
    pendingScroll = false
  }

  // 根据距底距离同步自动跟随状态；触摸浏览期间仅在回到底部后恢复
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

  // 请求节流后的自动贴底，不抢占用户已暂停的阅读位置
  function requestScrollToBottom() {
    if (!pageVisible) return
    if (!autoFollowEnabled.value || touchPaused) return
    scheduleRaf()
  }

  // 合并同一帧内的多次贴底请求
  function scheduleRaf() {
    pendingScroll = true
    if (rafScheduled) return
    rafScheduled = true
    requestAnimationFrame(processPendingScroll)
  }

  // 按最大 20fps 处理已合并的滚动请求
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

  // 立即执行一次无动画贴底，并标记为程序滚动
  function doScrollNow() {
    const container = getContainer()
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

  // 内容观察器使用的同步贴底路径
  function syncScrollToBottom() {
    const container = getContainer()
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

  // 强制开启跟随并立即定位到底部，用于首次进入或切换会话
  function scrollToBottomImmediate() {
    const container = getContainer()
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

  // 响应用户点击“回到底部”，平滑滚动并恢复跟随
  function userScrollToBottom() {
    const container = getContainer()
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

  // 响应用户主动跳到顶部，并暂停自动跟随
  function userScrollToTop() {
    const container = getContainer()
    if (!container) return
    autoFollowEnabled.value = false
    programmaticScroll = true
    container.scrollTo({ top: 0, behavior: 'smooth' })
    setTimeout(() => { programmaticScroll = false }, 500)
    updateNavButtons()
  }

  // 页面隐藏时保存跟随状态，恢复可见后按原状态继续
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

  // 窗口失焦时暂停自动滚动并记录此前状态
  function onWindowBlur() {
    pageVisible = false
    followBeforeHidden = autoFollowEnabled.value
  }

  // 窗口重新聚焦时按失焦前状态恢复跟随
  function onWindowFocus() {
    pageVisible = true
    if (followBeforeHidden && !touchPaused) {
      autoFollowEnabled.value = true
      doScrollNow()
    }
  }

  // 触摸开始即暂停，避免惯性浏览被流式更新抢回底部
  function onTouchStart() {
    touchPaused = true
    autoFollowEnabled.value = false
  }

  // 根据严格底部距离更新“回到底部”按钮可见性
  function updateNavButtons() {
    const container = getContainer()
    if (!container) return
    const scrollTop = container.scrollTop
    const scrollHeight = container.scrollHeight
    const clientHeight = container.clientHeight
    const isAtBottom = scrollHeight - scrollTop - clientHeight <= 5
    showScrollToBottom.value = !isAtBottom
  }

  // 判断容器是否位于可恢复自动跟随的底部阈值内
  function isNearBottom() {
    const container = getContainer()
    if (!container) return true
    return container.scrollHeight - container.scrollTop - container.clientHeight < NEAR_BOTTOM_THRESHOLD
  }

  // 清理节流状态并以当前高度作为新的观察基线
  function reset() {
    pendingScroll = false
    rafScheduled = false
    const container = getContainer()
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
