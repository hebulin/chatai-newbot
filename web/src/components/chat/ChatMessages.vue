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
              <!-- 重新生成中的目标消息：原位渲染新版本的流式内容（原回答保留为旧版本，不新增气泡） -->
              <template v-if="isRegenTarget(idx)">
                <div v-if="streamingMsg.reasoning_content" class="thinking-block" :class="{ collapsed: streamingMsg.thinkingTime }">
                  <div class="thinking-header" @click="toggleThinking($event)">
                    <span class="arrow">▼</span>
                    {{ streamingMsg.thinkingTime ? t('messages.thoughtFor', { s: streamingMsg.thinkingTime }) : t('messages.thinking') }}
                  </div>
                  <div
                    :ref="setStreamThinkingRef"
                    class="thinking-body"
                    data-streaming-thinking
                    v-html="renderMd(streamingMsg.reasoning_content)"
                  ></div>
                </div>
                <div v-if="streamingMsg.content" class="answer-content" v-html="renderMd(streamingMsg.content)"></div>
                <div v-if="!streamingMsg.content && !streamingMsg.reasoning_content" class="loading-dots"><span></span><span></span><span></span></div>
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
            </template>
          </div>
        </div>
        <!-- footer -->
        <div class="msg-footer" v-if="!isRegenTarget(idx)">
          <template v-if="msg.role === 'assistant'">
            <span v-if="msg.modelName" class="msg-model-name">{{ msg.modelName }}</span>
            <span v-if="msg.completionTokens" class="msg-token-info">Token≈{{ fmtToken(msg.completionTokens) }}</span>
            <!-- 回答版本切换器：仅在存在多个版本时显示；点击数字展开版本列表（比较入口） -->
            <span v-if="versionInfoOf(idx)" class="msg-version-switcher">
              <button class="version-nav-btn" :disabled="versionInfoOf(idx).currentIndex <= 0"
                      @click="switchVersion(idx, -1)" :title="t('messages.prevVersion')" :aria-label="t('messages.prevVersion')">‹</button>
              <button class="version-indicator" @click="toggleVersionList(idx)"
                      :title="t('messages.versionList')" :aria-expanded="versionListIdx === idx">
                {{ versionInfoOf(idx).currentIndex + 1 }}/{{ versionInfoOf(idx).total }}
              </button>
              <button class="version-nav-btn" :disabled="versionInfoOf(idx).currentIndex >= versionInfoOf(idx).total - 1"
                      @click="switchVersion(idx, 1)" :title="t('messages.nextVersion')" :aria-label="t('messages.nextVersion')">›</button>
              <!-- 版本列表浮层：展示各版本生成时间/模型/状态，点击切换 -->
              <div v-if="versionListIdx === idx" class="version-list-pop" role="menu">
                <div v-for="(v, vi) in versionInfoOf(idx).list" :key="v.versionId"
                     class="version-list-item" :class="{ current: vi === versionInfoOf(idx).currentIndex }"
                     role="menuitem" tabindex="0"
                     @click="pickVersion(idx, v.versionId)" @keydown.enter="pickVersion(idx, v.versionId)">
                  <span class="version-list-no">{{ vi + 1 }}</span>
                  <span class="version-list-time">{{ v.time || '-' }}</span>
                  <span class="version-list-model">{{ v.modelName || '' }}</span>
                  <span v-if="v.status && v.status !== 'done'" class="version-list-status">{{ t('messages.versionInterrupted') }}</span>
                </div>
              </div>
            </span>
          </template>
          <template v-else>
            <span v-if="msg.promptTokens" class="msg-token-info">Token≈{{ fmtToken(msg.promptTokens) }}</span>
          </template>
          <button class="footer-copy-btn" @click="$emit('copy', msg.content)" :title="t('messages.copy')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>
          </button>
          <!-- 创建分支：仅 AI 消息显示。分支点须落在回答消息上，保证新分支以完整"问+答"成对记录结尾；
               若允许在用户消息处分支，新分支会以未回答的提问结尾，破坏成对结构 -->
          <button v-if="msg.role === 'assistant' && !isStreaming" class="footer-copy-btn" @click="$emit('branch', idx)" title="从此处创建会话分支" aria-label="从此处创建会话分支">
            <svg width="14" height="14" viewBox="0 0 1024 1024" fill="currentColor"><path d="M647.836735 181.812245a114.938776 114.938776 0 1 1 229.877551 0 114.938776 114.938776 0 0 1-229.877551 0zM762.77551 129.567347a52.244898 52.244898 0 1 0 0 104.489796 52.244898 52.244898 0 0 0 0-104.489796zM188.081633 181.812245a114.938776 114.938776 0 1 1 229.877551 0 114.938776 114.938776 0 0 1-229.877551 0zM303.020408 129.567347a52.244898 52.244898 0 1 0 0 104.489796 52.244898 52.244898 0 0 0 0-104.489796zM188.081633 850.546939a114.938776 114.938776 0 1 1 229.877551 0 114.938776 114.938776 0 0 1-229.877551 0z m114.938775-52.244898a52.244898 52.244898 0 1 0 0 104.489796 52.244898 52.244898 0 0 0 0-104.489796z"/><path d="M334.367347 234.057143v358.149224c7.523265-5.955918 15.36-11.514776 23.384816-16.718367 45.599347-29.737796 105.325714-54.16751 161.394939-77.113469l1.880816-0.752327c58.305306-23.865469 112.702694-46.247184 152.805878-72.348735 40.646531-26.498612 57.594776-50.949224 57.594775-76.277551v-114.938775h62.693878v114.938775c0 58.284408-40.521143 99.11902-86.078694 128.794123-45.599347 29.716898-105.325714 54.146612-161.394939 77.092571l-1.880816 0.773225c-58.305306 23.844571-112.702694 46.226286-152.805878 72.348734-40.646531 26.477714-57.594776 50.949224-57.594775 76.277551v62.693878h-62.693878V234.057143h62.693878z"/></svg>
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

    <!-- 流式消息（重新生成模式下不追加到末尾，而是在目标消息原位渲染） -->
    <div v-if="isStreaming && streamingMsg && regenIdx < 0" class="msg-wrapper assistant">
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
            <div
              :ref="setStreamThinkingRef"
              class="thinking-body"
              data-streaming-thinking
              v-html="renderMd(streamingMsg.reasoning_content)"
            ></div>
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
import { computed, onUpdated, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { renderMermaidBlocks, processSpecialContent, handleMermaidToolbarClick } from '@/composables/useMarkdown'
import { useScrollFollow } from '@/composables/useScrollFollow'
import { useSearchHighlight } from '@/composables/useSearchHighlight'
import { useSpeech } from '@/composables/useSpeech'
import { useTheme } from '@/composables/useTheme'
import { useModelsStore } from '@/stores/models'
import { useChatStore } from '@/stores/chat'
import { getUserProfile } from '@/api/user'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  isStreaming: { type: Boolean, default: false },
  streamingMsg: { type: Object, default: null },
  // 重新生成中的目标消息绝对下标（>=0 时流式内容在该消息原位渲染）；-1 表示非重新生成模式
  regenIdx: { type: Number, default: -1 },
  // 列表起始下标：因长会话渲染窗口裁剪，此处 idx 为展示列表下标，
  // 需加上 startIndex 换算回完整会话消息列表的绝对下标，用于生成全局唯一 DOM ID
  startIndex: { type: Number, default: 0 },
  // 当前需高亮的用户消息 DOM ID（点击侧边栏锚点跳转后置位，动画结束后清除）
  highlightId: { type: String, default: '' },
  // 会话内搜索关键字与当前命中消息绝对下标：用于在渲染后的富文本中精确标记关键词
  searchKeyword: { type: String, default: '' },
  searchActiveIndex: { type: Number, default: -1 }
})

const emit = defineEmits(['copy', 'lightbox', 'regenerate', 'edit-resend', 'preview-html', 'branch', 'select-version'])

const { t } = useI18n()

// ===== 回答版本切换 =====
// 版本信息由 chat store 统一维护（versions 数组 + currentVersionId），
// 这里仅做展示与切换事件转发；版本列表浮层同一时刻只展开一个
const chatStore = useChatStore()
const versionListIdx = ref(-1)

// 读取指定展示下标消息的版本视图信息（仅多版本时返回，单版本/非 AI 消息不显示切换器）
function versionInfoOf(displayIdx) {
  const chatId = chatStore.currentChatId
  if (!chatId) return null
  const info = chatStore.getMessageVersions(chatId, absIdxOf(displayIdx))
  if (!info || info.total < 2) return null
  return info
}

// 上一版本/下一版本快捷切换
function switchVersion(displayIdx, delta) {
  const info = versionInfoOf(displayIdx)
  if (!info) return
  const next = info.currentIndex + delta
  if (next < 0 || next >= info.total) return
  emitVersionSelect(displayIdx, info.list[next].versionId)
}

// 展开/收起版本列表浮层
function toggleVersionList(displayIdx) {
  versionListIdx.value = versionListIdx.value === displayIdx ? -1 : displayIdx
}

// 从版本列表选择版本
function pickVersion(displayIdx, versionId) {
  versionListIdx.value = -1
  emitVersionSelect(displayIdx, versionId)
}

// 转发版本选择事件（携带完整列表绝对下标），由 ChatView 决定直接切换或确认后创建分支
function emitVersionSelect(displayIdx, versionId) {
  emit('select-version', { absIdx: absIdxOf(displayIdx), versionId })
}

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

// 渲染 Markdown 与搜索高亮（离屏 HTML 标记，不触碰已挂载 DOM；详见 useSearchHighlight 注释）
const { renderMd, formatUserContent } = useSearchHighlight(() => ({
  keyword: props.searchKeyword,
  activeIndex: props.searchActiveIndex,
  startIndex: props.startIndex
}))

// 生成消息的 DOM 锚点 ID：用于锚点跳转与会话内搜索定位时 getElementById
// 形如 msg-anchor-3（3 为完整消息列表中的绝对下标，跨渲染窗口保持稳定）
function msgDomId(displayIdx) {
  return 'msg-anchor-' + (displayIdx + props.startIndex)
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
// 当前展示下标是否为"重新生成"的目标消息（该位置原位渲染流式新版本内容）
function isRegenTarget(displayIdx) {
  return props.isStreaming && props.streamingMsg && props.regenIdx >= 0
    && absIdxOf(displayIdx) === props.regenIdx
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
const streamThinkingRef = shallowRef(null)
const thinkingScrollFollow = useScrollFollow(streamThinkingRef, { nearBottomThreshold: 30 })

// 绑定当前唯一的流式思考滚动容器；使用函数 ref 避免重新生成分支位于 v-for 内时模板 ref 变成数组
function setStreamThinkingRef(el) {
  streamThinkingRef.value = el || null
}

// 条件渲染创建或替换思考容器时重新绑定与正文相同的滚动控制器；消失时解绑旧节点
watch(streamThinkingRef, (el) => {
  if (!el) {
    thinkingScrollFollow.unbindEvents()
    return
  }
  thinkingScrollFollow.init()
  thinkingScrollFollow.scrollToBottomImmediate()
}, { flush: 'post' })

// 思考内容增长时在 DOM 更新后请求贴底；MutationObserver 继续覆盖 Markdown 后处理产生的高度变化
watch(() => props.streamingMsg && props.streamingMsg.reasoning_content, () => {
  thinkingScrollFollow.requestScrollToBottom()
}, { flush: 'post' })

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

/* 回答版本切换器（‹ n/m › + 版本列表浮层） */
.msg-version-switcher {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-left: 4px;
}
.version-nav-btn,
.version-indicator {
  border: none;
  background: transparent;
  color: var(--fg-3, #999);
  cursor: pointer;
  font-size: 12px;
  padding: 1px 5px;
  border-radius: 5px;
  line-height: 1.5;
}
.version-nav-btn:hover:not(:disabled),
.version-indicator:hover {
  color: var(--fg, #333);
  background: var(--bg-2, rgba(0,0,0,.05));
}
.version-nav-btn:disabled {
  opacity: 0.35;
  cursor: default;
}
.version-indicator {
  font-variant-numeric: tabular-nums;
}
.version-list-pop {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  min-width: 240px;
  max-width: 320px;
  background: var(--bg-2, #fff);
  border: 1px solid var(--border, rgba(0,0,0,.1));
  border-radius: 10px;
  box-shadow: 0 8px 28px rgba(0,0,0,.14);
  padding: 5px;
  z-index: 60;
}
.version-list-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 9px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12px;
  color: var(--fg-2, #666);
  white-space: nowrap;
  overflow: hidden;
}
.version-list-item:hover,
.version-list-item:focus-visible {
  background: var(--bg, rgba(0,0,0,.04));
  outline: none;
}
.version-list-item.current {
  color: var(--primary, #4285f4);
  font-weight: 600;
}
.version-list-no {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--bg, rgba(0,0,0,.06));
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
}
.version-list-time {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}
.version-list-model {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
}
.version-list-status {
  flex-shrink: 0;
  color: #e6a23c;
  font-size: 11px;
}
</style>
