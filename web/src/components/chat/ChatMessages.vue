<template>
  <div class="chat-messages" ref="containerRef">
    <template v-for="(msg, idx) in messages" :key="idx">
      <div class="msg-wrapper" :class="msg.role">
        <div v-if="msg.time" class="msg-time-top">{{ msg.time }}</div>
        <div class="msg-row">
          <div class="msg-avatar" :class="msg.role === 'user' ? 'user-av' : 'ai-av'">
            <img :src="msg.role === 'user' ? userAvatarSrc : aiAvatarSrc" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />
          </div>
          <div class="msg-bubble" :data-raw="msg.content">
            <template v-if="msg.role === 'user'">
              <div v-if="msg.images && msg.images.length" class="user-msg-images">
                <img v-for="(img, i) in msg.images" :key="i" class="user-msg-img" :src="img" alt="发送的图片" @click="$emit('lightbox', img)" />
              </div>
              <span v-html="formatUserContent(msg.content)"></span>
            </template>
            <template v-else>
              <div v-if="msg.reasoning_content" class="thinking-block" :class="{ collapsed: msg.thinkingTime }">
                <div class="thinking-header" @click="toggleThinking($event)">
                  <span class="arrow">▼</span>
                  {{ msg.interrupted ? '思考被中断' : (msg.thinkingTime ? '深度思考 · ' + msg.thinkingTime + 's' : '正在思考...') }}
                </div>
                <div class="thinking-body" v-html="renderMd(msg.reasoning_content)"></div>
              </div>
              <div v-if="msg.content" class="answer-content" v-html="renderMd(msg.content)"></div>
            </template>
          </div>
        </div>
        <!-- footer -->
        <div class="msg-footer">
          <template v-if="msg.role === 'assistant'">
            <span v-if="msg.modelName" class="msg-model-name">{{ msg.modelName }}</span>
            <span v-if="msg.completionTokens" class="msg-token-info">Token≈{{ fmtToken(msg.completionTokens) }}</span>
          </template>
          <template v-else>
            <span v-if="msg.promptTokens" class="msg-token-info">Token≈{{ fmtToken(msg.promptTokens) }}</span>
          </template>
          <button class="footer-copy-btn" @click="$emit('copy', msg.content)" title="复制">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
        </div>
      </div>
    </template>

    <!-- 流式消息 -->
    <div v-if="isStreaming && streamingMsg" class="msg-wrapper assistant">
      <div class="msg-row">
        <div class="msg-avatar ai-av">
          <img :src="aiAvatarSrc" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />
        </div>
        <div class="msg-bubble">
          <div v-if="streamingMsg.reasoning_content" class="thinking-block" :class="{ collapsed: streamingMsg.thinkingTime }">
            <div class="thinking-header" @click="toggleThinking($event)">
              <span class="arrow">▼</span>
              {{ streamingMsg.thinkingTime ? '深度思考 · ' + streamingMsg.thinkingTime + 's' : '正在思考...' }}
            </div>
            <div class="thinking-body" v-html="renderMd(streamingMsg.reasoning_content)"></div>
          </div>
          <div v-if="streamingMsg.content" class="answer-content" v-html="renderMd(streamingMsg.content)"></div>
          <div v-if="!streamingMsg.content && !streamingMsg.reasoning_content" class="loading-dots"><span></span><span></span><span></span></div>
        </div>
      </div>
      <div class="msg-footer">
        <span v-if="streamingMsg.modelName" class="msg-model-name">{{ streamingMsg.modelName }}</span>
        <span class="msg-token-info msg-token-loading">Token计算中<span class="token-wave"><span></span><span></span><span></span></span></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onUpdated, onMounted, onUnmounted, ref } from 'vue'
import { renderMarkdown, escapeHtml, renderMermaidBlocks, processSpecialContent, handleMermaidToolbarClick } from '@/composables/useMarkdown'
import { useTheme } from '@/composables/useTheme'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  streamingMsg: { type: Object, default: null }
})

const emit = defineEmits(['copy', 'lightbox'])

const { getTheme } = useTheme()
const containerRef = ref(null)

const userAvatarSrc = computed(() => getTheme() === 'dark' ? '/icons/user_ss.svg' : '/icons/user.svg')
const aiAvatarSrc = computed(() => getTheme() === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg')

function renderMd(text) {
  return renderMarkdown(text)
}

function formatUserContent(content) {
  return escapeHtml(content).replace(/\n/g, '<br>')
}

function fmtToken(n) {
  if (!n) return '0'
  return Number(n).toLocaleString('en-US')
}

function toggleThinking(e) {
  const block = e.currentTarget.parentElement
  block.classList.toggle('collapsed')
}

// DOM 更新后：代码高亮 + mermaid 渲染（流式高频更新时 mermaid 渲染防抖 300ms，避免频繁 parse/排队）
let renderTimer = null
onUpdated(() => {
  const el = containerRef.value
  if (!el) return
  processSpecialContent(el)
  if (renderTimer) clearTimeout(renderTimer)
  renderTimer = setTimeout(() => {
    if (containerRef.value) renderMermaidBlocks(containerRef.value, getTheme())
  }, 300)
})

// mermaid 工具栏事件委托
function onContainerClick(e) {
  handleMermaidToolbarClick(e)
}
// AI 图片点击放大事件转发（processSpecialContent 派发的 CustomEvent，统一走 lightbox 灯箱）
function onLightbox(e) {
  if (e.detail && e.detail.src) emit('lightbox', e.detail.src)
}
onMounted(() => {
  containerRef.value?.addEventListener('click', onContainerClick)
  containerRef.value?.addEventListener('lightbox', onLightbox)
})
onUnmounted(() => {
  containerRef.value?.removeEventListener('click', onContainerClick)
  containerRef.value?.removeEventListener('lightbox', onLightbox)
  if (renderTimer) clearTimeout(renderTimer)
})
</script>
