<template>
  <div class="chat-messages" ref="containerRef">
    <template v-for="(msg, idx) in messages" :key="idx">
      <!-- 上下文清除分隔线：后续对话不再携带此线之前的历史 -->
      <div v-if="msg.role === 'divider'" class="context-divider">
        <span class="context-divider-label">{{ t('messages.contextCleared') }}</span>
      </div>
      <div
        v-else
        class="msg-wrapper"
        :class="[msg.role, { 'msg-highlight': highlightId === msgDomId(idx) }]"
        :id="msgDomId(idx)"
      >
        <div v-if="msg.time" class="msg-time-top">{{ msg.time }}</div>
        <div class="msg-row">
          <div class="msg-avatar" :class="msg.role === 'user' ? 'user-av' : 'ai-av'">
            <img :src="msg.role === 'user' ? userAvatarSrc : aiAvatarSrc" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />
          </div>
          <div class="msg-bubble" :data-raw="msg.content">
            <template v-if="msg.role === 'user'">
              <div v-if="msg.images && msg.images.length" class="user-msg-images">
                <img v-for="(img, i) in msg.images" :key="i" class="user-msg-img" :src="img" :alt="t('messages.sentImage')" @click="$emit('lightbox', img)" />
              </div>
              <div v-if="msg.attachments && msg.attachments.length" class="user-msg-files">
                <span v-for="(att, i) in msg.attachments" :key="i" class="user-msg-file" :title="att.name">
                  <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
                  {{ att.name }}
                </span>
              </div>
              <span v-html="formatUserContent(msg.content, idx)"></span>
            </template>
            <template v-else>
              <!-- 错误气泡：请求失败/流内错误统一样式，与正常回答区分 -->
              <div v-if="msg.isError" class="error-bubble">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12"/><line x1="12" y1="16" x2="12.01" y2="16"/></svg>
                <span v-html="formatUserContent(msg.content, idx)"></span>
              </div>
              <template v-else>
                <!-- 历史消息必然已完成：固定显示“已思考”，不依赖 thinkingTime 判断状态，
                     兼容旧数据中 thinkingTime 缺失/为 0 时误显“正在思考”的问题 -->
                <div v-if="msg.reasoning_content" class="thinking-block collapsed">
                  <div class="thinking-header" @click="toggleThinking($event)">
                    <span class="arrow">▼</span>
                    {{ msg.interrupted ? t('messages.thinkingInterrupted') : (msg.thinkingTime ? t('messages.thoughtFor', { s: msg.thinkingTime }) : t('messages.thought')) }}
                  </div>
                  <div class="thinking-body" v-html="renderMd(msg.reasoning_content)"></div>
                </div>
                <div v-if="msg.content" class="answer-content" v-html="renderMd(msg.content, idx)"></div>
              </template>
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
          <button class="footer-copy-btn" @click="$emit('copy', msg.content)" :title="t('messages.copy')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
          <button v-if="!isStreaming" class="footer-copy-btn" @click="$emit('branch', idx)" title="从此处创建会话分支" aria-label="从此处创建会话分支">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M6 3v7a4 4 0 0 0 4 4h8"/><path d="m15 11 3 3-3 3"/><circle cx="6" cy="3" r="2"/></svg>
          </button>
          <!-- AI 消息：TTS 朗读（播放中显示停止、暂停中显示继续，状态由全局 useSpeech 驱动） -->
          <button v-if="msg.role === 'assistant' && !msg.isError && speechSupported" class="footer-copy-btn" :class="{ speaking: speechIsCurrent(idx) }" @click="speechToggle(idx, msg.content)" :title="speechBtnTitle(idx)">
            <svg v-if="speechIsPlaying(idx)" width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
            <svg v-else-if="speechIsPaused(idx)" width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><polygon points="7 4 20 12 7 20 7 4"/></svg>
            <svg v-else width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5"/><path d="M15.54 8.46a5 5 0 0 1 0 7.07"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14"/></svg>
          </button>
          <!-- 用户消息：编辑重发 -->
          <button v-if="msg.role === 'user' && !isStreaming" class="footer-copy-btn" @click="$emit('edit-resend', idx)" :title="t('messages.editResend')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/></svg>
          </button>
          <!-- AI 消息：重新生成（仅最后一条） -->
          <button v-if="msg.role === 'assistant' && idx === messages.length - 1 && !isStreaming" class="footer-copy-btn" @click="$emit('regenerate', idx)" :title="t('messages.regenerate')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="23 4 23 10 17 10"/><path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"/></svg>
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
              {{ streamingMsg.thinkingTime ? t('messages.thoughtFor', { s: streamingMsg.thinkingTime }) : t('messages.thinking') }}
            </div>
            <div class="thinking-body" ref="streamThinkingRef" @scroll="onThinkingScroll" v-html="renderMd(streamingMsg.reasoning_content)"></div>
          </div>
          <div v-if="streamingMsg.content" class="answer-content" v-html="renderMd(streamingMsg.content)"></div>
          <div v-if="!streamingMsg.content && !streamingMsg.reasoning_content" class="loading-dots"><span></span><span></span><span></span></div>
        </div>
      </div>
      <div class="msg-footer">
        <span v-if="streamingMsg.modelName" class="msg-model-name">{{ streamingMsg.modelName }}</span>
        <span class="msg-token-info msg-token-loading">{{ t('messages.tokenCalc') }}<span class="token-wave"><span></span><span></span><span></span></span></span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onUpdated, onMounted, onUnmounted, ref, watch, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMarkdown, escapeHtml, renderMermaidBlocks, processSpecialContent, handleMermaidToolbarClick } from '@/composables/useMarkdown'
import { useSpeech } from '@/composables/useSpeech'
import { useTheme } from '@/composables/useTheme'
import { useModelsStore } from '@/stores/models'
import { getUserProfile } from '@/api/user'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  streamingMsg: { type: Object, default: null },
  // 列表起始下标：因长会话渲染窗口裁剪，此处 idx 为展示列表下标，
  // 需加上 startIndex 换算回完整会话消息列表的绝对下标，用于生成全局唯一 DOM ID
  startIndex: { type: Number, default: 0 },
  // 当前需高亮的用户消息 DOM ID（点击侧边栏锚点跳转后置位，动画结束后清除）
  highlightId: { type: String, default: '' },
  // 会话内搜索关键字与当前命中消息绝对下标：用于在渲染后的富文本中精确标记关键词
  searchKeyword: { type: String, default: '' },
  searchActiveIndex: { type: Number, default: -1 }
})

const emit = defineEmits(['copy', 'lightbox', 'regenerate', 'edit-resend', 'preview-html', 'branch'])

const { t } = useI18n()

const { getTheme } = useTheme()
const modelsStore = useModelsStore()
const containerRef = ref(null)
const avatarProfile = ref({ avatarType: 'default', avatarValue: '' })

const userAvatarSrc = computed(() => avatarProfile.value.avatarType === 'svg' && avatarProfile.value.avatarValue
  ? 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(avatarProfile.value.avatarValue)
  : (getTheme() === 'dark' ? '/icons/user_ss.svg' : '/icons/user.svg'))
const aiAvatarSrc = computed(() => modelsStore.botAvatarSvg
  ? 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(modelsStore.botAvatarSvg)
  : (getTheme() === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg'))

// 渲染 Markdown；仅历史消息正文传入展示下标并叠加搜索高亮，思考过程与流式消息保持原样
function renderMd(text, displayIdx = -1) {
  return highlightRenderedHtml(renderMarkdown(text), displayIdx)
}

// 生成消息的 DOM 锚点 ID：用于锚点跳转与会话内搜索定位时 getElementById
// 形如 msg-anchor-3（3 为完整消息列表中的绝对下标，跨渲染窗口保持稳定）
function msgDomId(displayIdx) {
  return 'msg-anchor-' + (displayIdx + props.startIndex)
}

// 在离屏 HTML 中包裹关键词，返回给 v-html 一次性渲染，避免直接修改 Vue 已挂载 DOM
function highlightRenderedHtml(html, displayIdx) {
  if (displayIdx < 0) return html
  const keyword = props.searchKeyword.trim()
  if (!keyword) return html
  const keywordLower = keyword.toLowerCase()
  const root = document.createElement('div')
  root.innerHTML = html
  const isActive = displayIdx + props.startIndex === props.searchActiveIndex
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const textNodes = []
  let node = walker.nextNode()
  while (node) {
    const parent = node.parentElement
    // 代码、公式、图表与操作按钮有独立的后处理流程，搜索标记不介入其内部 DOM
    const skip = parent?.closest('script, style, svg, button, input, textarea, pre, code, .code-header, .mermaid-container, .katex')
    if (!skip && node.textContent?.toLowerCase().includes(keywordLower)) textNodes.push(node)
    node = walker.nextNode()
  }

  textNodes.forEach(textNode => {
    const text = textNode.textContent || ''
    const lower = text.toLowerCase()
    const fragment = document.createDocumentFragment()
    let cursor = 0
    let matchAt = lower.indexOf(keywordLower)
    while (matchAt !== -1) {
      if (matchAt > cursor) fragment.append(document.createTextNode(text.slice(cursor, matchAt)))
      const mark = document.createElement('mark')
      mark.className = `msg-search-match${isActive ? ' msg-search-match-active' : ''}`
      mark.textContent = text.slice(matchAt, matchAt + keyword.length)
      fragment.append(mark)
      cursor = matchAt + keyword.length
      matchAt = lower.indexOf(keywordLower, cursor)
    }
    if (cursor < text.length) fragment.append(document.createTextNode(text.slice(cursor)))
    textNode.replaceWith(fragment)
  })
  return root.innerHTML
}

// 转义用户/错误消息并按需叠加离屏搜索高亮，确保 v-html 输入安全
function formatUserContent(content, displayIdx = -1) {
  const html = escapeHtml(content).replace(/\n/g, '<br>')
  return highlightRenderedHtml(html, displayIdx)
}

function fmtToken(n) {
  if (!n) return '0'
  return Number(n).toLocaleString('en-US')
}

function toggleThinking(e) {
  const block = e.currentTarget.parentElement
  block.classList.toggle('collapsed')
}

// ===== TTS 朗读（全局单例 useSpeech）=====
// 喇叭按钮三种状态：非当前消息=喇叭（点击播放）；当前消息播放中=方块（点击停止）；
// 当前消息已暂停=三角（点击继续）。悬浮播放窗在 ChatView 层渲染，与本按钮共享同一状态
const speech = useSpeech()
const speechSupported = speech.speechSupported

// 展示下标换算为完整消息列表绝对下标（与 DOM 锚点 id 同一约定，供全局状态匹配）
function absIdxOf(displayIdx) {
  return displayIdx + props.startIndex
}
function speechIsCurrent(displayIdx) {
  return speech.isCurrent(absIdxOf(displayIdx))
}
function speechIsPlaying(displayIdx) {
  return speechIsCurrent(displayIdx) && speech.status.value === 'playing'
}
function speechIsPaused(displayIdx) {
  return speechIsCurrent(displayIdx) && speech.status.value === 'paused'
}
function speechToggle(displayIdx, content) {
  speech.toggle(absIdxOf(displayIdx), content)
}
function speechBtnTitle(displayIdx) {
  if (speechIsPlaying(displayIdx)) return t('messages.stopReading')
  if (speechIsPaused(displayIdx)) return t('messages.resumeReading')
  return t('messages.readAloud')
}

// ===== 思考内容区域自动滚动跟随（逻辑与聊天窗口一致：可随时手动打断，滚回底部时恢复跟随） =====
const streamThinkingRef = ref(null)
const THINKING_NEAR_BOTTOM = 30
let thinkingAutoFollow = true
let thinkingProgrammatic = false

function onThinkingScroll(e) {
  if (thinkingProgrammatic) return
  const el = e.target
  // 手动滚动：离底即暂停跟随，回到底部附近则恢复
  thinkingAutoFollow = el.scrollHeight - el.scrollTop - el.clientHeight < THINKING_NEAR_BOTTOM
}

// 思考内容增长时跟随到底部
watch(() => props.streamingMsg && props.streamingMsg.reasoning_content, () => {
  nextTick(() => {
    const el = streamThinkingRef.value
    if (!el || !thinkingAutoFollow) return
    thinkingProgrammatic = true
    el.scrollTop = el.scrollHeight
    requestAnimationFrame(() => { thinkingProgrammatic = false })
  })
})

// 新一轮流式开始时重置为自动跟随
watch(() => props.isStreaming, (v) => { if (v) thinkingAutoFollow = true })

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
// HTML 代码块预览事件转发（代码头“预览”按钮派发的 CustomEvent，交由 ChatView 打开右侧预览面板）
function onHtmlPreview(e) {
  if (e.detail && e.detail.code != null) emit('preview-html', e.detail.code)
}
// 刷新用户头像资料
async function loadAvatarProfile() {
  try {
    const res = await getUserProfile()
    if (res?.success) avatarProfile.value = { ...avatarProfile.value, ...(res.data || {}) }
  } catch (e) { /* 回退默认头像 */ }
}
// 接收个人设置保存后的头像更新
function onProfileUpdated(event) {
  avatarProfile.value = { ...avatarProfile.value, ...(event.detail || {}) }
}
onMounted(() => {
  containerRef.value?.addEventListener('click', onContainerClick)
  containerRef.value?.addEventListener('lightbox', onLightbox)
  containerRef.value?.addEventListener('html-preview', onHtmlPreview)
  window.addEventListener('user-profile-updated', onProfileUpdated)
  loadAvatarProfile()
})
onUnmounted(() => {
  containerRef.value?.removeEventListener('click', onContainerClick)
  containerRef.value?.removeEventListener('lightbox', onLightbox)
  containerRef.value?.removeEventListener('html-preview', onHtmlPreview)
  window.removeEventListener('user-profile-updated', onProfileUpdated)
  if (renderTimer) clearTimeout(renderTimer)
})
</script>

<style scoped>
/* 错误气泡：红色警示风格，与正常回答区分 */
.error-bubble {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: #e5484d;
  background: rgba(229, 72, 77, 0.08);
  border: 1px solid rgba(229, 72, 77, 0.35);
  border-radius: 8px;
  padding: 10px 12px;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
}
.error-bubble svg {
  flex-shrink: 0;
  margin-top: 3px;
}

/* 上下文清除分隔线 */
.context-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px auto;
  max-width: 760px;
  color: var(--ink-3, #999);
  font-size: 12px;
}
.context-divider::before,
.context-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--line-1, rgba(0,0,0,.1));
}
.context-divider-label {
  flex-shrink: 0;
  padding: 2px 10px;
  border-radius: 10px;
  background: var(--surface-2, rgba(0,0,0,.04));
  white-space: nowrap;
}
</style>
