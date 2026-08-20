<template>
  <!-- 消息锚点直尺刻度：贴在滚动容器右侧边缘（滚动条左侧），垂直铺满视口高度。
       刻度按固定像素间距紧凑排列（像真直尺刻度线挨着），不随消息数量拉伸分散。
       鼠标移入任一锚点刻度热区即显示浮窗列出所有消息，浮窗内可继续选择并点击跳转，
       浮窗保持显示不消失；鼠标离开刻度与浮窗区域后才延迟关闭 -->
  <div
    v-if="anchors.length >= 1"
    class="message-ruler"
    @wheel.passive="onWheel"
  >
    <div class="message-ruler-track" ref="trackRef">
      <button
        v-for="(tick, i) in ticks"
        :key="tick.id"
        class="ruler-tick"
        :class="{ 'is-current': i === activeIdx }"
        :style="{ top: tick.top + 'px' }"
        :title="tick.summary"
        @click="$emit('jump', tick.messageDomId)"
        @mouseenter="activeIdx = i"
      ></button>
    </div>

    <!-- 锚点消息列表面板：移入任何锚点刻度后显示，列出所有用户消息 -->
    <Transition name="ruler-panel">
      <div
        v-if="showPanel"
        ref="panelRef"
        class="ruler-panel"
      >
        <div class="ruler-panel-list" ref="panelListRef">
          <button
            v-for="(tick, i) in ticks"
            :key="tick.id"
            class="ruler-panel-item"
            :class="{ 'is-current': i === activeIdx }"
            @click="onItemClick(tick.messageDomId)"
          >
            <span class="ruler-panel-item-index">{{ i + 1 }}</span>
            <span class="ruler-panel-item-text">{{ tick.content }}</span>
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'

const props = defineProps({
  // 滚动容器 DOM 引用（chatContainerRef）
  scrollContainerRef: { type: Object, default: null },
  // 当前会话完整消息列表（含被裁剪的更早消息）
  messages: { type: Array, default: () => [] },
  // 被裁剪的更早消息数量（用于换算绝对下标生成 DOM ID）
  hiddenCount: { type: Number, default: 0 }
})

const emit = defineEmits(['jump'])

// 用户消息锚点数据：从完整消息列表提取用户消息，生成 DOM ID 与摘要
const anchors = computed(() => {
  const list = []
  props.messages.forEach((m, idx) => {
    if (m.role !== 'user') return
    const content = String(m.content || '')
    list.push({
      // 绝对下标 = 显示下标 + 被裁剪数量，与 ChatMessages.vue 的 msgDomId 保持一致
      absIdx: idx + props.hiddenCount,
      content,
      summary: content.length > 15 ? content.substring(0, 15) + '...' : content
    })
  })
  return list
})

// 当前可视刻度数据：含 DOM ID 与在直尺轨道中的像素位置（px）
// 刻度按固定像素间距紧凑排列（像真直尺刻度线挨着），不随消息数量拉伸分散
const ticks = ref([])
const activeIdx = ref(-1)
const trackRef = ref(null)
let scrollEl = null

// 刻度固定间距（px）：刻度线之间的垂直间隔，紧凑挨着
const TICK_SPACING = 12
// 轨道上下留白（px）
const TRACK_PAD = 10

// 浮窗显示状态与延迟关闭控制
const showPanel = ref(false)
const panelRef = ref(null)
const panelListRef = ref(null)
let panelCloseTimer = null
// 浮窗关闭延迟（ms）：足够鼠标在刻度与浮窗之间移动，避免间隙误关闭
const PANEL_CLOSE_DELAY = 300

// 重新计算所有刻度：固定像素间距紧凑排列，刻度组整体在轨道中垂直居中；
// 仅当刻度数量多到超出轨道可用高度时，才压缩间距以免溢出
function recalc() {
  const list = anchors.value
  const n = list.length
  if (n === 0) { ticks.value = []; return }
  // 轨道可用高度（扣除上下留白）
  const trackH = trackRef.value ? trackRef.value.clientHeight : 0
  const availH = Math.max(0, trackH - TRACK_PAD * 2)
  // 固定间距下的组总高
  const totalH = (n - 1) * TICK_SPACING
  // 超出可用高度时压缩间距（至少保留 2px），否则保持固定紧凑间距
  let spacing = TICK_SPACING
  if (totalH > availH && availH > 0) {
    spacing = Math.max(2, availH / (n - 1))
  }
  // 组起始位置：垂直居中于可用区域内
  const groupH = (n - 1) * spacing
  const startY = TRACK_PAD + (availH - groupH) / 2
  ticks.value = list.map((a, i) => ({
    id: 'msg-anchor-' + a.absIdx,
    content: a.content,
    summary: a.summary,
    messageDomId: 'msg-anchor-' + a.absIdx,
    // 固定间距紧凑排列：top 为像素值
    top: Math.round(startY + i * spacing)
  }))
}

// 滚动时更新当前高亮刻度：找出视口中心最接近的用户消息（按消息 DOM 实际位置判定）
function onScroll() {
  if (!scrollEl || ticks.value.length === 0) return
  const center = scrollEl.scrollTop + scrollEl.clientHeight / 2
  let best = -1
  let bestDist = Infinity
  ticks.value.forEach((tk, i) => {
    const el = document.getElementById(tk.messageDomId)
    if (!el) return
    // 元素中心点在内容坐标系中的位置
    const elCenter = el.offsetTop + el.offsetHeight / 2
    const dist = Math.abs(elCenter - center)
    if (dist < bestDist) { bestDist = dist; best = i }
  })
  activeIdx.value = best
}

// 滚轮事件转发到聊天容器：直尺区域滚动时驱动主聊天区滚动
function onWheel(e) {
  if (!scrollEl) return
  scrollEl.scrollTop += e.deltaY
}

// 判断屏幕坐标点是否位于任一锚点刻度热区或浮窗区域内
function isPointInHotZone(x, y) {
  // 刻度热区（逐个 getBoundingClientRect 判定，刻度热区外不触发）
  if (trackRef.value) {
    const tickEls = trackRef.value.querySelectorAll('.ruler-tick')
    for (const el of tickEls) {
      const r = el.getBoundingClientRect()
      if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
    }
  }
  // 浮窗区域
  if (panelRef.value) {
    const r = panelRef.value.getBoundingClientRect()
    if (x >= r.left && x <= r.right && y >= r.top && y <= r.bottom) return true
  }
  return false
}

// 全局鼠标移动监听：鼠标位于刻度热区/浮窗内时显示浮窗，离开后延迟关闭。
// 相比 mouseenter/mouseleave 的优势：不依赖事件冒泡边界，热区之间无缝衔接；
// 浮窗关闭后鼠标仍在热区内移动时可立即重新触发显示（mouseenter 则需先离开再进入）
function onGlobalMouseMove(e) {
  if (isPointInHotZone(e.clientX, e.clientY)) {
    clearTimeout(panelCloseTimer)
    panelCloseTimer = null
    if (!showPanel.value) showPanel.value = true
  } else if (showPanel.value && !panelCloseTimer) {
    panelCloseTimer = setTimeout(() => {
      panelCloseTimer = null
      showPanel.value = false
    }, PANEL_CLOSE_DELAY)
  }
}

// 点击浮窗中的消息项：触发跳转，浮窗保持显示不消失
function onItemClick(messageDomId) {
  emit('jump', messageDomId)
  // 跳转后滚动面板列表使当前项可见（可选，提升体验）
  nextTick(() => {
    scrollPanelToCurrent()
  })
}

// 滚动浮窗列表使当前高亮项可见
function scrollPanelToCurrent() {
  const listEl = panelListRef.value
  if (!listEl) return
  const currentItem = listEl.querySelector('.ruler-panel-item.is-current')
  if (currentItem) {
    currentItem.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
}

let rafId = null
function scheduleScroll() {
  if (rafId) return
  rafId = requestAnimationFrame(() => {
    rafId = null
    onScroll()
  })
}

let resizeObserver = null
let mutationObserver = null

function bind() {
  if (!scrollEl) return
  scrollEl.addEventListener('scroll', scheduleScroll, { passive: true })
  // 刻度位置依赖轨道高度，轨道尺寸变化（窗口 resize/布局变化）时重算紧凑排列
  resizeObserver = new ResizeObserver(() => { recalc() })
  if (trackRef.value) resizeObserver.observe(trackRef.value)
  // 内容 DOM 变化（流式输出/图片加载/展开更早消息）只更新当前高亮刻度
  mutationObserver = new MutationObserver(() => { onScroll() })
  mutationObserver.observe(scrollEl, { childList: true, subtree: true, attributes: false })
  // 全局 mousemove 监听浮窗触发与关闭（事件在 window 上，覆盖刻度与浮窗全部区域）
  window.addEventListener('mousemove', onGlobalMouseMove, { passive: true })
  recalc()
  onScroll()
}

function unbind() {
  if (scrollEl) scrollEl.removeEventListener('scroll', scheduleScroll)
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (mutationObserver) { mutationObserver.disconnect(); mutationObserver = null }
  window.removeEventListener('mousemove', onGlobalMouseMove)
  if (rafId) { cancelAnimationFrame(rafId); rafId = null }
  clearTimeout(panelCloseTimer)
  panelCloseTimer = null
}

onMounted(async () => {
  await nextTick()
  scrollEl = props.scrollContainerRef
  bind()
})

onUnmounted(unbind)

// 滚动容器引用变化或消息列表变化时重新绑定与计算
watch(() => props.scrollContainerRef, async () => {
  unbind()
  await nextTick()
  scrollEl = props.scrollContainerRef
  bind()
})

// 锚点数据变化时重算刻度：监听 anchors computed 而非 props.messages 引用——
// 新会话内 addMessage 是对同一数组 push（引用不变），watch 引用 + deep:false 不会触发，
// 导致新会话无论多少轮对话刻度都不出现（ticks 为空），必须切换会话（数组引用变化）才显示；
// anchors 是 computed，消息内容/数量/hiddenCount 变化都会重新求值返回新数组，watch 必然触发
watch(anchors, async () => {
  await nextTick()
  // 直尺容器由 v-if="anchors.length > 1" 控制：bind() 时若尚未渲染（新会话首轮），
  // trackRef 为 null 导致 ResizeObserver 未挂；此处补挂，保证轨道尺寸变化可重算
  if (resizeObserver && trackRef.value) resizeObserver.observe(trackRef.value)
  recalc()
  onScroll()
})
</script>
