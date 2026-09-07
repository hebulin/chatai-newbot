import { computed, onUnmounted, ref } from 'vue'

const PREVIEW_RATIO_KEY = 'htmlPreviewRatio'

/** 读取并校验上次保存的桌面分栏比例。 */
function loadPreviewRatio() {
  try {
    const value = Number.parseFloat(localStorage.getItem(PREVIEW_RATIO_KEY))
    if (value >= 20 && value <= 80) return value
  } catch {
    // 无 localStorage 的环境使用默认比例。
  }
  return 50
}

/**
 * 管理 HTML 预览分栏的打开状态、持久化比例和指针拖拽副作用。
 * @param {{ isMobile: import('vue').Ref<boolean> }} options 响应式移动端状态
 */
export function useHtmlPreview({ isMobile }) {
  const htmlPreviewCode = ref(null)
  const chatSplitRef = ref(null)
  const dividerDragging = ref(false)
  const splitRatio = ref(loadPreviewRatio())
  let removeDragListeners = null

  const chatPaneStyle = computed(() => {
    if (htmlPreviewCode.value === null || isMobile.value) return {}
    return { flex: `0 0 calc(${splitRatio.value}% - 3px)` }
  })

  /** 打开指定 HTML 内容的隔离预览。 */
  function openHtmlPreview(code) {
    htmlPreviewCode.value = code
  }

  /** 关闭 HTML 预览面板。 */
  function closeHtmlPreview() {
    htmlPreviewCode.value = null
  }

  /** 清理当前分割线拖拽监听。 */
  function stopDividerDrag() {
    if (removeDragListeners) removeDragListeners()
    removeDragListeners = null
    dividerDragging.value = false
  }

  /** 开始桌面端分割线拖拽，并将最终比例持久化。 */
  function startDividerDrag(event) {
    if (isMobile.value) return
    event.preventDefault()
    const splitElement = chatSplitRef.value
    if (!splitElement) return
    stopDividerDrag()
    dividerDragging.value = true
    const rect = splitElement.getBoundingClientRect()
    const onMove = pointerEvent => {
      const ratio = ((pointerEvent.clientX - rect.left) / rect.width) * 100
      splitRatio.value = Math.min(80, Math.max(20, ratio))
    }
    const finishDrag = () => {
      stopDividerDrag()
      try {
        localStorage.setItem(PREVIEW_RATIO_KEY, String(Math.round(splitRatio.value)))
      } catch {
        // 浏览器禁止持久化时仍保留本次内存状态。
      }
    }
    removeDragListeners = () => {
      document.removeEventListener('pointermove', onMove)
      document.removeEventListener('pointerup', finishDrag)
      window.removeEventListener('pointercancel', finishDrag)
      window.removeEventListener('blur', finishDrag)
    }
    document.addEventListener('pointermove', onMove)
    document.addEventListener('pointerup', finishDrag)
    window.addEventListener('pointercancel', finishDrag)
    window.addEventListener('blur', finishDrag)
  }

  onUnmounted(stopDividerDrag)

  return {
    htmlPreviewCode,
    chatSplitRef,
    dividerDragging,
    chatPaneStyle,
    openHtmlPreview,
    closeHtmlPreview,
    startDividerDrag
  }
}
