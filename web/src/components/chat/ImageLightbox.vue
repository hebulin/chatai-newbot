<template>
  <Teleport to="body">
    <div class="image-lightbox-overlay" @click.self="$emit('close')" @keydown.esc="$emit('close')">
      <button class="image-lightbox-close" @click="$emit('close')">✕</button>
      <div class="image-lightbox-toolbar">
        <button class="lightbox-btn" @click="zoomIn" title="放大">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
        </button>
        <button class="lightbox-btn" @click="zoomOut" title="缩小">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg>
        </button>
        <button class="lightbox-btn" @click="resetZoom" title="重置">1:1</button>
        <span class="lightbox-sep"></span>
        <button class="lightbox-btn" @click="download" title="下载">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
        </button>
      </div>
      <div class="image-lightbox-content" @wheel.prevent="onWheel" @mousedown="onMouseDown" :style="{ cursor: dragging ? 'grabbing' : 'grab' }">
        <img :src="src" class="image-lightbox-img" :style="{ transform: `translate(${tx}px, ${ty}px) scale(${scale})` }" />
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  src: { type: String, required: true }
})

const emit = defineEmits(['close'])

const scale = ref(1)
const tx = ref(0)
const ty = ref(0)
const dragging = ref(false)

let startX = 0, startY = 0, startTX = 0, startTY = 0

function zoomIn() { scale.value = Math.min(scale.value + 0.25, 5) }
function zoomOut() { scale.value = Math.max(scale.value - 0.25, 0.25) }
function resetZoom() { scale.value = 1; tx.value = 0; ty.value = 0 }

function onWheel(e) {
  const delta = e.deltaY > 0 ? -0.15 : 0.15
  scale.value = Math.max(0.25, Math.min(scale.value + delta, 5))
}

function onMouseDown(e) {
  dragging.value = true
  startX = e.clientX
  startY = e.clientY
  startTX = tx.value
  startTY = ty.value
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
}

function onMouseMove(e) {
  if (!dragging.value) return
  tx.value = startTX + (e.clientX - startX)
  ty.value = startTY + (e.clientY - startY)
}

function onMouseUp() {
  dragging.value = false
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
}

function download() {
  const a = document.createElement('a')
  a.href = props.src
  a.download = 'image-' + Date.now() + '.png'
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
}

function onKeydown(e) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('mousemove', onMouseMove)
  document.removeEventListener('mouseup', onMouseUp)
})
</script>

<!-- 灯箱样式统一维护在 styles/chat.css 的 IMAGE LIGHTBOX 区块，避免与全局样式重复定义导致定位冲突 -->
