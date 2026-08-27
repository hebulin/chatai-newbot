<template>
  <div class="chat-page">
    <div class="paper-grain" aria-hidden="true"></div>

    <!-- 顶栏 -->
    <header class="atelier-top">
      <div class="top-left">
        <button class="icon-btn toggle-sidebar-btn" @click="toggleSidebar" :aria-label="t('chat.sidebarAria')">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="3" y1="7" x2="21" y2="7"/><line x1="3" y1="13" x2="21" y2="13"/><line x1="3" y1="19" x2="14" y2="19"/></svg>
        </button>
        <div class="brand">
          <img class="brand-icon" :src="brandIconSrc" alt="AI" />
          <div class="brand-text">
            <span class="brand-name">Atelier</span>
            <span class="brand-sub">{{ chatTitle }}</span>
          </div>
        </div>
      </div>
      <div class="top-right">
        <button class="icon-btn msg-search-btn" @click="openMsgSearch" :title="t('chat.searchInChat')" :aria-label="t('chat.searchInChat')">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        </button>
        <button class="icon-btn share-chat-btn" @click="handleShareChat" :title="t('chat.shareChat')" :aria-label="t('sidebar.share')">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
        </button>
        <button class="theme-toggle-btn" @click="toggleTheme" :title="t('chat.toggleTheme')" :aria-label="t('chat.toggleTheme')">
          <span class="toggle-track"><span class="toggle-knob"></span></span>
        </button>
      </div>
    </header>

    <!-- 侧边栏 -->
    <ChatSidebar
      ref="chatSidebarRef"
      :class="{ open: sidebarOpen, collapsed: sidebarCollapsed }"
      @toggle="toggleSidebar"
      @new-chat="handleNewChat"
      @switch-chat="handleSwitchChat"
      @search-result-select="handleSidebarSearchResult"
      @delete-chat="handleDeleteChat"
      @share-chat="doShare"
      @open-settings="showSettings = true"
      @open-about="showAbout = true"
      @open-stats="showStats = true"
      @logout="handleLogout"
    />

    <!-- 遮罩 -->
    <div v-if="sidebarOpen && isMobile" class="overlay active" @click="toggleSidebar"></div>

    <!-- 主内容 -->
    <main class="main-content" :class="{ 'sidebar-collapsed': sidebarCollapsed && !isMobile }">
      <!-- 聊天区与 HTML 预览区的分栏容器：预览打开时左右分栏，拖动中间分割线调整比例 -->
      <div class="chat-split" :class="{ 'with-preview': htmlPreviewCode !== null, dragging: dividerDragging }" ref="chatSplitRef">
        <div class="chat-pane" :style="chatPaneStyle">
          <div class="chat-viewport">
            <!-- 会话内搜索栏：悬浮于对话区顶部，Enter 下一个 / Shift+Enter 上一个 / Esc 关闭 -->
            <div v-if="msgSearchOpen" class="msg-search-bar">
              <input
                ref="msgSearchInputRef"
                v-model="msgSearchKeyword"
                class="msg-search-input"
                :placeholder="t('chat.searchInChatPlaceholder')"
                autocomplete="off"
                @keydown.enter="nextMsgMatch"
                @keydown.shift.enter="prevMsgMatch"
              />
              <span class="msg-search-count">{{ msgSearchInfo }}</span>
              <button class="msg-search-nav" @click="prevMsgMatch" :title="t('chat.searchPrev')" :aria-label="t('chat.searchPrev')">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="18 15 12 9 6 15"/></svg>
              </button>
              <button class="msg-search-nav" @click="nextMsgMatch" :title="t('chat.searchNext')" :aria-label="t('chat.searchNext')">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
              </button>
              <button class="msg-search-nav" @click="closeMsgSearch" :title="t('common.cancel')" :aria-label="t('common.cancel')">
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <div class="chat-container" ref="chatContainerRef">
          <!-- 空会话引导：新会话未发送内容时展示，发出首条消息后自动消失 -->
          <div v-if="showWelcome" class="chat-welcome">
            <img class="chat-welcome-icon" :src="brandIconSrc" alt="AI" />
            <div class="chat-welcome-title">{{ t('chat.welcomeTitle') }}</div>
            <div class="chat-welcome-sub">{{ t('chat.welcomeSub') }}</div>
            <div class="chat-welcome-tips">
              <button v-for="(tip, i) in welcomeTips" :key="i" class="chat-welcome-tip" @click="applyWelcomeTip(tip)">{{ tip }}</button>
            </div>
          </div>
          <!-- 长会话性能：默认只渲染最近一窗口消息，更早的按需展开 -->
          <div v-if="hiddenCount > 0" class="load-earlier">
            <button class="load-earlier-btn" @click="loadEarlier">{{ t('chat.loadEarlier', { n: hiddenCount }) }}</button>
          </div>
          <ChatMessages
            :messages="displayMessages"
            :is-streaming="streamChat.isStreaming.value"
            :streaming-msg="streamingMsg"
            :regen-idx="regenStreamingIdx"
            :start-index="hiddenCount"
            :highlight-id="highlightMsgId"
            :search-keyword="msgSearchKeyword"
            :search-active-index="activeMsgSearchIndex"
            @copy="copyMsgContent"
            @lightbox="lightboxSrc = $event"
            @regenerate="handleRegenerate"
            @edit-resend="handleEditResend"
            @branch="handleCreateBranch"
            @select-version="handleSelectVersion"
            @preview-html="handlePreviewHtml"
          />
        </div>

        <!-- 消息锚点直尺刻度：贴在滚动容器右侧边缘，按用户消息在文档中的比例位置排布刻度，
             悬停预览完整内容，点击平滑滚动定位并高亮 -->
        <MessageRuler
          :scroll-container-ref="chatContainerRef"
          :messages="chatStore.currentMessages"
          :hidden-count="hiddenCount"
          @jump="handleJumpToMessage"
        />

        <!-- 滚动导航（对话区域右下角，不遮挡输入框） -->
        <div class="scroll-nav" v-show="scrollFollow.showScrollToBottom.value">
          <button class="scroll-nav-btn" @click="scrollFollow.userScrollToBottom()" :title="t('chat.backToBottom')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
        </div>

        <!-- 语音播放悬浮窗：悬浮于对话区底部（输入框上方），展示正在播放的内容；
             支持暂停/继续、定位到对应消息、关闭停止（全局单例，与消息喇叭按钮状态联动） -->
        <div v-if="speechStatus !== 'idle'" class="speech-float-bar">
          <span class="speech-float-status">{{ speechStatus === 'paused' ? t('speech.paused') : t('speech.playing') }}</span>
          <span class="speech-float-text" :title="speechCurrent?.text">{{ speechTextPreview }}</span>
          <button class="speech-float-btn" @click="toggleSpeechPause" :title="speechStatus === 'paused' ? t('speech.resume') : t('speech.pause')">
            <svg v-if="speechStatus === 'playing'" width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="4" width="4" height="16" rx="1"/><rect x="14" y="4" width="4" height="16" rx="1"/></svg>
            <svg v-else width="13" height="13" viewBox="0 0 24 24" fill="currentColor"><polygon points="7 4 20 12 7 20 7 4"/></svg>
          </button>
          <button class="speech-float-btn" @click="locateSpeechMsg" :title="t('speech.locate')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="7"/><circle cx="12" cy="12" r="2" fill="currentColor" stroke="none"/><line x1="12" y1="1" x2="12" y2="5"/><line x1="12" y1="19" x2="12" y2="23"/><line x1="1" y1="12" x2="5" y2="12"/><line x1="19" y1="12" x2="23" y2="12"/></svg>
          </button>
          <button class="speech-float-btn" @click="speech.stop()" :title="t('speech.close')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>

        <!-- 切换会话加载缓冲：懒加载拉取正文/长会话首屏渲染期间的视觉过渡 -->
        <div v-if="chatSwitchLoading" class="chat-switch-loading">
          <span class="chat-switch-spinner"></span>
          <span>{{ t('chat.chatLoading') }}</span>
        </div>
          </div>

          <!-- 当前会话同步提示（同步超过 3 秒才显示） -->
          <div v-if="syncTipVisible" class="chat-sync-tip">{{ t('chat.syncTip') }}</div>

          <!-- 上下文占用估算：与后端 ContextBudgetService 同一保守估算规则（CJK 1 token/字，ASCII 1/4 字符） -->
          <div v-if="contextUsageInfo" class="context-usage-tip" :class="{ warn: contextUsageInfo.ratio > 0.85 }">
            {{ contextUsageInfo.text }}
          </div>

          <!-- 中断回答操作条：明确提供“重新生成”与“基于已生成内容继续”（新请求） -->
          <div v-if="lastInterruptedMsg" class="interrupted-action-bar" role="alert">
            <span class="interrupted-action-text">{{ t('chat.interruptedBanner') }}</span>
            <button class="interrupted-action-btn" @click="handleRegenerateInterrupted">{{ t('chat.regenerate') }}</button>
            <button class="interrupted-action-btn primary" @click="handleContinueGeneration">{{ t('chat.continueGeneration') }}</button>
          </div>

          <!-- 多端同步冲突横幅：冲突会话的本地与服务端版本均已保留，由用户明确选择 -->
          <div v-if="chatStore.syncConflicts.length" class="sync-conflict-bar" role="alert">
            <div class="sync-conflict-head">{{ t('chat.syncConflictTip', { n: chatStore.syncConflicts.length }) }}</div>
            <div v-for="c in chatStore.syncConflicts" :key="c.chatId" class="sync-conflict-item">
              <span class="sync-conflict-name">
                {{ c.title || chatStore.chatTitle(c.chatId) }}
                <em v-if="c.reason === 'deleted'">{{ t('chat.syncConflictDeleted') }}</em>
              </span>
              <button class="sync-conflict-btn" @click="resolveConflict(c.chatId, false)">{{ t('chat.syncUseServer') }}</button>
              <button class="sync-conflict-btn primary" @click="resolveConflict(c.chatId, true)">{{ t('chat.syncUseLocal') }}</button>
            </div>
          </div>

          <!-- 输入区 -->
          <ChatInput
            ref="chatInputRef"
            :is-streaming="streamChat.isStreaming.value"
            :supports-thinking="modelsStore.currentModelSupportsThinking"
            :supports-multimodal="modelsStore.currentModelSupportsMultimodal"
            @send="handleSend"
            @stop="handleStop"
            @clear-context="handleClearContext"
            @thinking-change="handleThinkingChange"
          />
        </div>

        <!-- HTML 预览：可拖拽分割线 + 右侧预览面板（默认与聊天区各占 50%） -->
        <template v-if="htmlPreviewCode !== null">
          <div class="split-divider" @pointerdown="onDividerPointerDown"></div>
          <div class="html-preview-pane">
            <div class="html-preview-header">
              <span class="html-preview-title">{{ t('chat.htmlPreviewTitle') }}</span>
              <button class="html-preview-close" @click="closeHtmlPreview" :title="t('chat.closePreview')">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
              </button>
            </div>
            <!-- sandbox 仅放行脚本执行，不放行同源访问，隔离 AI 生成代码对主站的影响 -->
            <iframe class="html-preview-frame" sandbox="allow-scripts" referrerpolicy="no-referrer" :srcdoc="htmlPreviewCode"></iframe>
          </div>
        </template>
      </div>
    </main>

    <!-- 设置弹窗 -->
    <SettingsModal v-if="showSettings" @close="showSettings = false" @logout="handleLogout" />

    <!-- 关于弹窗 -->
    <AboutModal v-if="showAbout" @close="showAbout = false" />

    <!-- 数据统计弹窗 -->
    <UsageStatsModal v-if="showStats" @close="showStats = false" />

    <!-- 分享会话弹窗 -->
    <ShareModal v-if="shareChatId" :chat-id="shareChatId" @close="shareChatId = null" />

    <!-- 图片灯箱 -->
    <ImageLightbox v-if="lightboxSrc" :src="lightboxSrc" @close="lightboxSrc = null" />
  </div>
</template>

<script setup>
import '@/styles/chat.css'
import { ref, computed, onMounted, onUnmounted, nextTick, watch, h } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, ElCheckbox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useChatStore } from '@/stores/chat'
import { useModelsStore } from '@/stores/models'
import { useAuthStore } from '@/stores/auth'
import { useStreamChat } from '@/composables/useStreamChat'
import { useScrollFollow } from '@/composables/useScrollFollow'
import { useSpeech } from '@/composables/useSpeech'
import { useTheme } from '@/composables/useTheme'
import { logout as apiLogout } from '@/api/auth'
import { generateChatTitle, fetchAnnouncement } from '@/api/chat'
import ChatSidebar from '@/components/chat/ChatSidebar.vue'
import ChatMessages from '@/components/chat/ChatMessages.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import MessageRuler from '@/components/chat/MessageRuler.vue'
import SettingsModal from '@/components/chat/SettingsModal.vue'
import AboutModal from '@/components/chat/AboutModal.vue'
import UsageStatsModal from '@/components/chat/UsageStatsModal.vue'
import ImageLightbox from '@/components/chat/ImageLightbox.vue'
import ShareModal from '@/components/chat/ShareModal.vue'

const router = useRouter()
const chatStore = useChatStore()
const modelsStore = useModelsStore()
const authStore = useAuthStore()
const streamChat = useStreamChat()
const { toggleTheme, getTheme } = useTheme()
const { t } = useI18n()

const chatContainerRef = ref(null)
const scrollFollow = useScrollFollow(chatContainerRef)

const sidebarOpen = ref(false)
const sidebarCollapsed = ref(false)
const chatSidebarRef = ref(null)
const isMobile = ref(window.innerWidth <= 768)
const showSettings = ref(false)
const showAbout = ref(false)
const showStats = ref(false)
const lightboxSrc = ref(null)
// ===== HTML 代码块预览面板 =====
// 点击 html 代码块头部的“预览”按钮打开：聊天区与预览区默认各占 50%，
// 拖动中间分割线可调整比例（20%~80%），比例持久化到 localStorage
const htmlPreviewCode = ref(null)
const chatSplitRef = ref(null)
const dividerDragging = ref(false)
const PREVIEW_RATIO_KEY = 'htmlPreviewRatio'
const splitRatio = ref((() => {
  try {
    const v = parseFloat(localStorage.getItem(PREVIEW_RATIO_KEY))
    if (v >= 20 && v <= 80) return v
  } catch (e) { /* ignore */ }
  return 50
})())
// 预览打开时（桌面端）聊天区按分割比例定宽；关闭或移动端时恢复自适应撑满
const chatPaneStyle = computed(() => {
  if (htmlPreviewCode.value === null || isMobile.value) return {}
  return { flex: '0 0 calc(' + splitRatio.value + '% - 3px)' }
})
function handlePreviewHtml(code) {
  htmlPreviewCode.value = code
}
function closeHtmlPreview() {
  htmlPreviewCode.value = null
}
// 分割线拖拽：pointer 事件全局监听，拖动期间给容器加 dragging class
// （CSS 同步禁用 iframe 指针事件，避免指针移入 iframe 后丢失 move 事件）
function onDividerPointerDown(e) {
  if (isMobile.value) return
  e.preventDefault()
  const splitEl = chatSplitRef.value
  if (!splitEl) return
  dividerDragging.value = true
  const rect = splitEl.getBoundingClientRect()
  const onMove = (ev) => {
    const ratio = ((ev.clientX - rect.left) / rect.width) * 100
    splitRatio.value = Math.min(80, Math.max(20, ratio))
  }
  const onUp = () => {
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    dividerDragging.value = false
    try { localStorage.setItem(PREVIEW_RATIO_KEY, String(Math.round(splitRatio.value))) } catch (err) { /* ignore */ }
  }
  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
}
// ===== 语音播放悬浮窗（全局单例 useSpeech）=====
// 播放状态与消息工具栏喇叭按钮共享：浮窗展示正在播放的内容，
// 支持暂停/继续、定位跳转、关闭停止；切换会话需确认，继续切换则停止播放
const speech = useSpeech()
const speechStatus = speech.status
const speechCurrent = speech.current
// 浮窗展示的播放内容预览（截断 60 字，完整文本挂 title 悬停查看）
const speechTextPreview = computed(() => {
  const text = speechCurrent.value ? speechCurrent.value.text : ''
  return text.length > 60 ? text.substring(0, 60) + '…' : text
})
function toggleSpeechPause() {
  if (speechStatus.value === 'paused') speech.resume()
  else speech.pause()
}
// 定位按钮：跳转到正在播放的消息开头（block:'start' 顶部对齐，而非居中）；
// 目标在渲染窗口外时 jumpToAbsIndex 会自动展开
function locateSpeechMsg() {
  if (!speechCurrent.value) return
  jumpToAbsIndex(speechCurrent.value.absIdx, 'start')
}
// 切换会话前确认：有正在播放/暂停的朗读时提示，继续切换将停止播放
async function confirmSpeechStopIfPlaying() {
  if (speechStatus.value === 'idle') return true
  try {
    await ElMessageBox.confirm(t('speech.switchChatConfirm'), t('speech.switchChatTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
    speech.stop()
    return true
  } catch { return false }
}
const isDeepThinking = ref(false)
// 本轮对话是否开启联网搜索（重新生成/编辑重发时沿用上次选择）
const isWebSearch = ref(false)

const streamingMsg = ref(null)
const syncTipVisible = ref(false)
// 重新生成中的目标消息绝对下标（>=0 时流式内容在该消息原位渲染，而非列表末尾追加新气泡）
const regenStreamingIdx = ref(-1)

// 解决多端同步冲突：useLocal=true 保留本地版本（已删会话走显式恢复），false 采用服务端版本
async function resolveConflict(chatId, useLocal) {
  chatStore.resolveSyncConflict(chatId, useLocal)
  await nextTick()
  scrollFollow.requestScrollToBottom()
}
// 当前需高亮的用户消息锚点 ID（点击侧边栏锚点跳转后置位，动画结束后清除）
const highlightMsgId = ref('')
// 高亮清除定时器：重复跳转同一消息时取消上一次清除，避免新高亮被提前清掉
let highlightClearTimer = null
// 切换会话加载缓冲层（懒加载拉取正文期间显示）
const chatSwitchLoading = ref(false)
// 快速切换会话竞态控制：仅最新一次切换生效，切换时中断上一会话尚未完成的正文加载，
// 避免陈旧的 /chat/history/single 请求超时后堆积报错
let switchSeq = 0
let switchAbort = null

// ===== 长会话渲染窗口：默认只渲染最近 50 条，点“加载更早消息”每次再展开 100 条 =====
const RENDER_WINDOW = 50
const RENDER_BATCH = 100
const visibleCount = ref(RENDER_WINDOW)
const hiddenCount = computed(() => Math.max(0, chatStore.currentMessages.length - visibleCount.value))
const displayMessages = computed(() => {
  const msgs = chatStore.currentMessages
  return hiddenCount.value > 0 ? msgs.slice(hiddenCount.value) : msgs
})

// 切换/新建会话时重置渲染窗口与高亮标记，并检查目标会话是否有可恢复的流式草稿
watch(() => chatStore.currentChatId, () => {
  visibleCount.value = RENDER_WINDOW
  highlightMsgId.value = ''
  regenStreamingIdx.value = -1
  if (highlightClearTimer) { clearTimeout(highlightClearTimer); highlightClearTimer = null }
  restoreStreamDraftIfAny()
})

// ===== 空会话引导：无任何消息且未在流式输出时展示欢迎内容与建议提问 =====
const chatInputRef = ref(null)
const showWelcome = computed(() =>
  chatStore.isChatHistoryLoaded &&
  !streamChat.isStreaming.value &&
  chatStore.currentMessages.length === 0
)
const welcomeTips = computed(() => [
  t('chat.welcomeTip1'),
  t('chat.welcomeTip2'),
  t('chat.welcomeTip3'),
  t('chat.welcomeTip4')
])
// 点击建议：回填到输入框供编辑后发送，不直接发出
function applyWelcomeTip(tip) {
  chatInputRef.value?.setInput(tip)
}

// 展开更早消息并保持当前阅读位置（补齐新增内容的高度差）
async function loadEarlier() {
  const el = chatContainerRef.value
  const prevHeight = el ? el.scrollHeight : 0
  const prevTop = el ? el.scrollTop : 0
  visibleCount.value += RENDER_BATCH
  await nextTick()
  if (el) el.scrollTop = prevTop + (el.scrollHeight - prevHeight)
}

const brandIconSrc = computed(() => {
  if (modelsStore.botAvatarSvg) {
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(modelsStore.botAvatarSvg)
  }
  const theme = getTheme()
  return theme === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg'
})

const chatTitle = computed(() => {
  const meta = chatStore.chatMeta[chatStore.currentChatId] || {}
  if (meta.title && meta.title.trim()) return meta.title
  const msgs = chatStore.currentMessages
  const firstUser = msgs.find(m => m.role === 'user')
  if (firstUser) {
    return firstUser.content.substring(0, 30) + (firstUser.content.length > 30 ? '...' : '')
  }
  return t('chat.newChatTitle')
})

onMounted(async () => {
  // 初始化
  if (window.innerWidth <= 768) {
    sidebarCollapsed.value = true
  }
  await chatStore.loadFromServer()
  await modelsStore.loadModels()
  // 恢复当前会话可能存在的流式草稿（断网/刷新中断的部分回答）
  restoreStreamDraftIfAny()
  await nextTick()
  scrollFollow.init()
  // 刷新/首次进入时定位到当前会话最新消息：init 只绑定事件不主动滚动，
  // 纯文本会话无后续 DOM 变化时 MutationObserver 不会触发，导致停在顶部
  scrollFollow.scrollToBottomImmediate()
  // 兜底：首屏图片/字体加载完成后高度可能继续增长且不触发 DOM 变更，
  // 延迟再贴底一次（requestScrollToBottom 会尊重用户已主动上滑的状态）
  setTimeout(() => scrollFollow.requestScrollToBottom(), 300)

  window.addEventListener('resize', handleResize)
  window.addEventListener('keydown', handleGlobalKeydown)

  // 侧边栏多端自动同步：切回标签页/窗口聚焦时立即检测一次，前台期间低频轮询；
  // 页面隐藏时轮询自然跳过，版本未变化时仅一次轻量版本查询，开销可忽略
  window.addEventListener('focus', handleAutoSync)
  document.addEventListener('visibilitychange', handleAutoSync)
  autoSyncTimer = setInterval(handleAutoSync, AUTO_SYNC_INTERVAL_MS)

  // 拉取系统公告（异步不阻塞首屏）
  checkAnnouncement()
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  window.removeEventListener('focus', handleAutoSync)
  document.removeEventListener('visibilitychange', handleAutoSync)
  window.removeEventListener('keydown', handleGlobalKeydown)
  if (autoSyncTimer) clearInterval(autoSyncTimer)
  scrollFollow.unbindEvents()
  // 离开聊天页（如进入管理后台）时停止朗读，避免无 UI 状态下声音继续
  speech.stop()
})

// 全局快捷键：Ctrl/Cmd+K 展开侧边栏跨会话搜索；Ctrl/Cmd+F 打开会话内搜索；
// Esc 依次关闭搜索栏、HTML 预览面板
function handleGlobalKeydown(e) {
  const key = (e.key || '').toLowerCase()
  if ((e.ctrlKey || e.metaKey) && key === 'k') {
    e.preventDefault()
    if (isMobile.value) sidebarOpen.value = true
    else if (sidebarCollapsed.value) sidebarCollapsed.value = false
    nextTick(() => chatSidebarRef.value?.openSidebarSearch())
    return
  }
  if ((e.ctrlKey || e.metaKey) && key === 'f') {
    e.preventDefault()
    openMsgSearch()
    return
  }
  if (e.key === 'Escape') {
    if (msgSearchOpen.value) { closeMsgSearch(); return }
    if (htmlPreviewCode.value !== null) closeHtmlPreview()
  }
}

// 侧边栏自动同步轮询间隔（仅前台生效）
const AUTO_SYNC_INTERVAL_MS = 30000
let autoSyncTimer = null

// 检测并合并其他端的会话变更：bot 输出中不刷新（store 内部还有待上传/上传中守卫）；
// 当前会话被合并进其他端新消息时请求贴底（尊重用户已主动上滑的状态）
async function handleAutoSync() {
  if (document.hidden || streamChat.isStreaming.value || chatSwitchLoading.value) return
  const currentMerged = await chatStore.refreshFromServer()
  if (currentMerged) {
    await nextTick()
    scrollFollow.requestScrollToBottom()
  }
}

// 拉取公告并弹窗展示：勾选“以后不再提示”后该公告不再弹出（localStorage 永久记录）；
// 未勾选则每次登录都会提示（登录时清除 sessionStorage 标记，见 stores/auth.js），直到公告失效；
// 公告重新发布/重新生效（updatedAt 变化）后两类标记均失效，会再次提醒
async function checkAnnouncement() {
  try {
    const res = await fetchAnnouncement()
    const content = ((res && res.content) || '').trim()
    if (!content) return
    const key = `${(res && res.id) || ''}|${(res && res.updatedAt) || ''}`
    // 勾选过“以后不再提示”的公告不再弹出
    if (localStorage.getItem('announcement_dismissed') === key) return
    // 本次登录已提示过（刷新/切页不重复弹）
    if (sessionStorage.getItem('announcement_shown') === key) return
    const dontRemind = ref(false)
    await ElMessageBox({
      title: '📢 ' + (((res && res.title) || '').trim() || t('chat.announcementTitle')),
      message: () => h('div', null, [
        h('div', { style: 'white-space:pre-wrap;max-height:50vh;overflow:auto;' }, content),
        h(ElCheckbox, {
          modelValue: dontRemind.value,
          'onUpdate:modelValue': v => { dontRemind.value = v },
          label: t('chat.dontRemind'),
          style: 'margin-top:12px;'
        })
      ]),
      confirmButtonText: t('chat.iKnow'),
      showCancelButton: false,
      customStyle: { maxWidth: '520px' }
    }).catch(() => {})
    sessionStorage.setItem('announcement_shown', key)
    if (dontRemind.value) {
      localStorage.setItem('announcement_dismissed', key)
    }
    // 清理旧版已读标记（已改用 dismissed/shown 双标记机制）
    localStorage.removeItem('announcement_read_at')
  } catch (e) { /* 公告拉取失败不影响聊天 */ }
}

function handleResize() {
  isMobile.value = window.innerWidth <= 768
  if (window.innerWidth > 768) {
    sidebarOpen.value = false
  }
}

function toggleSidebar() {
  if (isMobile.value) {
    sidebarOpen.value = !sidebarOpen.value
    sidebarCollapsed.value = false
  } else {
    sidebarCollapsed.value = !sidebarCollapsed.value
  }
}

// 顶栏分享按钮：分享当前会话
function handleShareChat() {
  doShare(chatStore.currentChatId)
}

// 分享指定会话：校验会话内容后打开统一分享设置弹窗（所有选项在同一弹窗内展示）
const shareChatId = ref(null)
async function doShare(chatId) {
  // 懒加载模式：侧边栏分享未加载会话时先拉取正文
  try {
    await chatStore.ensureChatLoaded(chatId)
  } catch { return }
  const msgs = chatStore.chats[chatId] || []
  if (!msgs.some(m => m.role === 'user')) {
    ElMessage.info(t('chat.emptyNoShare'))
    return
  }
  shareChatId.value = chatId
}

async function handleNewChat() {
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return
  }
  // 如果当前会话为空，不再新建
  const msgs = chatStore.currentMessages
  if (msgs.length > 0 && msgs.some(m => m.role === 'user')) {
    // 有正在播放的朗读时先确认，继续切换将停止播放
    if (!(await confirmSpeechStopIfPlaying())) return
    chatStore.newChat()
    modelsStore.applyDefaultModel()
    isDeepThinking.value = false
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  } else {
    ElMessage.info(t('chat.alreadyNew'))
  }
}

// 切换目标会话并按调用方选项决定是否滚动到底部；返回值用于后续定位流程判断是否切换成功
async function handleSwitchChat(id, options = {}) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return false
  }
  if (id === chatStore.currentChatId) {
    if (isMobile.value) sidebarOpen.value = false
    return true
  }
  // 有正在播放的朗读时先确认，继续切换将停止播放
  if (!(await confirmSpeechStopIfPlaying())) return false
  // 中断上一次尚未完成的会话正文加载，防止陈旧请求堆积并在超时后误报
  if (switchAbort) switchAbort.abort()
  const controller = new AbortController()
  switchAbort = controller
  const mySeq = ++switchSeq
  // 懒加载：未加载会话先拉取正文再切换，期间盖 loading 缓冲网络与首屏渲染；
  // 已在内存的会话直接切换不显示 loading，失败则保持当前会话
  const needLoad = chatStore.chats[id] === undefined
  if (needLoad) chatSwitchLoading.value = true
  try {
    await chatStore.switchChatLazy(id, { signal: controller.signal })
    // 已被更晚的切换取代：放弃本次结果，避免覆盖最新会话
    if (mySeq !== switchSeq) return false
    if (isMobile.value) {
      sidebarOpen.value = false
    }
    // 等新会话消息渲染上屏后再撤 loading，渲染较慢时也有视觉缓冲
    await nextTick()
    if (options.scrollToBottom !== false) scrollFollow.scrollToBottomImmediate()
    return true
  } catch (e) {
    // 被主动中断（快速切走）或已被更晚切换取代：静默忽略，仅当前目标真正失败才提示
    if (e?.name === 'CanceledError' || mySeq !== switchSeq) return false
    ElMessage.error(t('chat.switchFailed'))
    return false
  } finally {
    // 仅最新一次切换负责收起 loading，防止旧切换提前撤销缓冲层
    if (mySeq === switchSeq) chatSwitchLoading.value = false
  }
}

// 打开侧边栏搜索结果：标题命中只切换会话，消息命中在加载会话后按绝对下标定位并高亮
async function handleSidebarSearchResult(result) {
  if (!result?.chatId) return
  const messageIndex = Number.isInteger(result.messageIndex) ? result.messageIndex : null
  const switched = await handleSwitchChat(result.chatId, { scrollToBottom: messageIndex === null })
  if (!switched || messageIndex === null) return
  await jumpToAbsIndex(messageIndex)
}

// 按绝对下标跳转定位到主聊天区对应消息并高亮
// block：'center' 居中（锚点/搜索跳转）、'start' 顶部对齐（语音浮窗定位到消息开头）
// 若目标消息在渲染窗口外（被裁剪的更早消息），先展开渲染窗口确保 DOM 存在，再滚动定位
async function jumpToAbsIndex(targetIdx, block = 'center') {
  const container = chatContainerRef.value
  // 若目标落在被裁剪的更早消息区间，逐步展开渲染窗口直至覆盖目标下标
  // （入场动画已改为仅最后一条消息生效，展开批量渲染不再产生整页抖动）
  if (targetIdx < hiddenCount.value) {
    while (targetIdx < hiddenCount.value && hiddenCount.value > 0) {
      visibleCount.value += RENDER_BATCH
    }
    await nextTick()
  }
  await nextTick()
  // 等待 mermaid/图片等异步内容渲染稳定后定位
  await nextTick()
  const domId = 'msg-anchor-' + targetIdx
  const el = document.getElementById(domId)
  if (!el) return
  // 暂停滚动跟随，避免跳转后被自动贴底逻辑覆盖
  scrollFollow.autoFollowEnabled.value = false
  el.scrollIntoView({ behavior: 'smooth', block })
  // 触发高亮动画：先清空再置位，保证重复点击同一消息时 class 重新切换、动画重新播放
  highlightMsgId.value = ''
  await nextTick()
  highlightMsgId.value = domId
  // 动画结束后清除高亮标记（保留 class 直至动画完成）；
  // 重复跳转时清掉旧定时器，避免旧定时器提前把新高亮清除
  if (highlightClearTimer) clearTimeout(highlightClearTimer)
  highlightClearTimer = setTimeout(() => { highlightMsgId.value = '' }, 2000)
  // 平滑滚动期间的 scroll 事件会把 autoFollow 重新置真（近底判定），此后异步内容
  // （mermaid/图片）高度变化会触发 MutationObserver 误贴底——表现为定位后约一秒页面抖动。
  // 滚动稳定后（scrollend，含兜底超时）再次压回 autoFollow 并重置内容高度基线，阻断误贴底
  let settled = false
  let fallbackTimer = null
  const settle = () => {
    if (settled) return
    settled = true
    scrollFollow.autoFollowEnabled.value = false
    scrollFollow.reset()
    if (container) container.removeEventListener('scrollend', settle)
    if (fallbackTimer) clearTimeout(fallbackTimer)
  }
  if (container) container.addEventListener('scrollend', settle, { once: true })
  fallbackTimer = setTimeout(settle, 1200)
}

// 点击消息锚点直尺刻度：跳转定位到主聊天区对应用户消息并高亮
async function handleJumpToMessage(domId) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return
  }
  // 解析目标消息在完整列表中的绝对下标
  const match = /^msg-anchor-(\d+)$/.exec(domId)
  if (!match) return
  await jumpToAbsIndex(parseInt(match[1], 10))
}

// ===== 会话内消息搜索 =====
// 顶部搜索按钮或 Ctrl+F 打开；匹配全部用户/AI 消息正文，Enter/按钮上下跳转定位
const msgSearchOpen = ref(false)
const msgSearchKeyword = ref('')
const msgSearchCursor = ref(-1)
const msgSearchInputRef = ref(null)

// 当前会话中匹配关键字的消息绝对下标列表（按时间顺序）
const msgSearchMatches = computed(() => {
  const kw = msgSearchKeyword.value.trim().toLowerCase()
  if (!kw) return []
  const list = []
  chatStore.currentMessages.forEach((m, idx) => {
    if (m.role !== 'user' && m.role !== 'assistant') return
    if (String(m.content || '').toLowerCase().includes(kw)) list.push(idx)
  })
  return list
})

const msgSearchInfo = computed(() => {
  const total = msgSearchMatches.value.length
  if (!msgSearchKeyword.value.trim()) return ''
  if (total === 0) return '0/0'
  return (msgSearchCursor.value + 1) + '/' + total
})

// 当前搜索结果对应的消息绝对下标，供消息组件标记当前关键词命中位置
const activeMsgSearchIndex = computed(() => {
  if (msgSearchCursor.value < 0) return -1
  return msgSearchMatches.value[msgSearchCursor.value] ?? -1
})

function openMsgSearch() {
  msgSearchOpen.value = true
  nextTick(() => msgSearchInputRef.value?.focus())
}

function closeMsgSearch() {
  msgSearchOpen.value = false
  msgSearchKeyword.value = ''
  msgSearchCursor.value = -1
}

// 跳转到搜索结果所在消息后，再将具体关键词高亮滚动到视口中央
async function jumpToSearchMatch(messageIndex) {
  await jumpToAbsIndex(messageIndex)
  await nextTick()
  const activeMark = document.querySelector(`#msg-anchor-${messageIndex} .msg-search-match-active`)
  activeMark?.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'nearest' })
}

// 关键字变化后定位到第一个匹配，并将具体关键词置于视口中央
watch(msgSearchKeyword, () => {
  if (!msgSearchMatches.value.length) { msgSearchCursor.value = -1; return }
  msgSearchCursor.value = 0
  jumpToSearchMatch(msgSearchMatches.value[0])
})

// 跳转到指定序号的匹配项（循环）
function jumpToMatch(cursor) {
  const matches = msgSearchMatches.value
  if (!matches.length) return
  msgSearchCursor.value = ((cursor % matches.length) + matches.length) % matches.length
  jumpToSearchMatch(matches[msgSearchCursor.value])
}

// 导航到下一个搜索命中项，末尾自动回到第一项
function nextMsgMatch() {
  jumpToMatch(msgSearchCursor.value + 1)
}

// 导航到上一个搜索命中项，第一项之前自动回到末项
function prevMsgMatch() {
  jumpToMatch(msgSearchCursor.value - 1)
}

// 切换会话时关闭搜索栏（匹配结果基于旧会话已无意义）
watch(() => chatStore.currentChatId, () => {
  if (msgSearchOpen.value) closeMsgSearch()
})

function handleDeleteChat(id) {
  ElMessageBox.confirm(t('chat.deleteConfirm'), t('chat.deleteTitle'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }).then(() => {
    chatStore.deleteChat(id)
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  }).catch(() => {})
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm(t('chat.logoutConfirm'), t('chat.logoutTitle'), {
      confirmButtonText: t('chat.logoutBtn'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
    apiLogout().catch(() => {})
    authStore.logout()
    router.push('/login')
  } catch (e) { /* cancelled */ }
}

async function handleSend({ text, images, attachments, deepThinking, webSearch }) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.stillStreaming'))
    return
  }
  if (!text && (!images || images.length === 0) && (!attachments || attachments.length === 0)) return
  if (!modelsStore.currentModelId) {
    ElMessage.warning(t('chat.pickModel'))
    return
  }

  isDeepThinking.value = deepThinking
  isWebSearch.value = !!webSearch
  const chatId = chatStore.currentChatId

  // 挂起全量同步：发送阶段只做当前会话同步，全量上传延后到 bot 输出结束
  chatStore.suspendSync()
  try {
    // 添加用户消息（本地先渲染）
    const userMsg = { role: 'user', content: text || (images && images.length ? t('chat.imgPlaceholder') : t('chat.attachPlaceholder')), time: nowStr() }
    if (images && images.length > 0) {
      userMsg.images = images.slice()
    }
    if (attachments && attachments.length > 0) {
      userMsg.attachments = attachments.slice()
    }
    chatStore.addMessage(chatId, userMsg)

    nextTick(() => scrollFollow.scrollToBottomImmediate())

    // 正式发送前先同步当前会话：其他端可能已在该会话新增记录，合并渲染后再对话
    await syncCurrentChatBeforeSend(chatId, 1)

    await startStream(chatId, deepThinking)
  } finally {
    chatStore.resumeSync()
  }
}

// 发送前同步当前会话；超过 3 秒在输入框上方提示“同步中”，完成后才开始对话；同步失败不阻塞发送
async function syncCurrentChatBeforeSend(chatId, pendingCount) {
  const tipTimer = setTimeout(() => { syncTipVisible.value = true }, 3000)
  try {
    const merged = await chatStore.syncCurrentChatFromServer(chatId, pendingCount)
    if (merged) {
      await nextTick()
      scrollFollow.scrollToBottomImmediate()
    }
  } catch (e) {
    console.error('当前会话同步失败:', e)
  } finally {
    clearTimeout(tipTimer)
    syncTipVisible.value = false
  }
}

// 基于当前会话已有消息历史发起流式请求（发送/重新生成/编辑重发共用）
// options.regenTarget={chatId, msgIdx}：重新生成模式——请求上下文排除该条旧回答，
// 结果作为该消息的新版本写入（成功成为当前版本，中断/失败保留部分结果但不覆盖当前版本）
async function startStream(chatId, deepThinking, options = {}) {
  const regenTarget = options.regenTarget || null
  // 只携带最后一个“清除上下文”分隔线之后的消息
  const source = chatStore.chats[chatId] || []
  let startIdx = 0
  for (let i = source.length - 1; i >= 0; i--) {
    if (source[i].role === 'divider') { startIdx = i + 1; break }
  }
  const messages = source.slice(startIdx)
    .filter((m, i) => {
      // 重新生成模式：排除待重新生成的那条旧回答，不把它带入上下文
      if (regenTarget && startIdx + i === regenTarget.msgIdx) return false
      return m.role === 'user' || (m.role === 'assistant' && !m.isError && m.content && m.content.trim())
    })
    .map(m => {
      const base = { role: m.role, content: m.content }
      if (m.role === 'user') {
        if (m.images && m.images.length > 0) base.images = m.images
        // 附件引用随历史消息携带，后端每轮读回解析文本合并进内容，不依赖多模态
        if (m.attachments && m.attachments.length > 0) base.attachments = m.attachments
      }
      return base
    })

  const requestBody = {
    modelConfigId: modelsStore.currentModelId,
    chatId, // 会话ID：服务端上下文摘要缓存键（旧服务端忽略该字段）
    messages,
    stream: true,
    deepThinking,
    webSearch: isWebSearch.value,
    // 会话绑定的角色提示词预设（后端优先于全局启用的预设）
    promptPresetId: (chatStore.chatMeta[chatId] || {}).promptPresetId || '',
    temperature: 0.7
  }

  streamingMsg.value = { role: 'assistant', content: '', reasoning_content: '', time: null, modelName: modelsStore.currentModelName }
  // 重新生成模式：流式内容在原消息位置渲染（原回答作为旧版本保留，新版本流式覆盖原位显示）
  regenStreamingIdx.value = regenTarget ? regenTarget.msgIdx : -1

  await streamChat.send(requestBody, {
    chatId,
    onUpdate: (data) => {
      streamingMsg.value = {
        role: 'assistant',
        content: data.content,
        reasoning_content: data.reasoning_content,
        thinkingTime: data.thinkingTime,
        time: nowStr(),
        modelName: modelsStore.currentModelName
      }
      scrollFollow.syncScrollToBottom()
    },
    onDone: (data) => {
      // 统一结算：保证每次请求只落一次消息；保留已收到的正文/思考/用量
      const hasContent = !!(data.content && data.content.trim())
      const hasReasoning = !!(data.reasoning_content && data.reasoning_content.trim())
      const interrupted = data.status && data.status !== 'done'
      // 重新生成模式：结果写入版本历史，不新增消息、不覆盖旧版本
      if (regenTarget) {
        if (hasContent || hasReasoning) {
          // 成功完成才设为当前版本；中断/失败保留部分结果为新版本但不切换
          chatStore.addMessageVersion(regenTarget.chatId, regenTarget.msgIdx, {
            content: hasContent ? data.content : '',
            reasoning_content: data.reasoning_content || undefined,
            thinkingTime: data.thinkingTime,
            modelName: modelsStore.currentModelName,
            time: nowStr(),
            status: data.status || 'done',
            usage: data.usage ? {
              promptTokens: data.usage.prompt_tokens || 0,
              completionTokens: data.usage.completion_tokens || 0,
              reasoningTokens: data.usage.completion_tokens_details?.reasoning_tokens || 0,
              cachedTokens: data.usage.prompt_tokens_details?.cached_tokens || 0
            } : undefined
          }, !interrupted)
        }
        if (interrupted) {
          ElMessage.warning(data.error || t('chat.answerInterrupted'))
        }
        streamingMsg.value = null
        regenStreamingIdx.value = -1
        nextTick(() => {
          scrollFollow.syncScrollToBottom()
          scrollFollow.updateNavButtons()
        })
        return
      }
      // 完全无内容且存在错误：只落一条错误气泡，不保存空回答
      if (!hasContent && !hasReasoning && data.error) {
        chatStore.addMessage(chatId, makeErrorMsg(data.error))
        streamingMsg.value = null
        regenStreamingIdx.value = -1
        nextTick(() => {
          scrollFollow.syncScrollToBottom()
          scrollFollow.updateNavButtons()
        })
        return
      }
      const content = hasContent
        ? data.content
        : (interrupted ? t('chat.answerInterrupted') : t('chat.noAnswer'))
      const msg = {
        role: 'assistant',
        content,
        reasoning_content: data.reasoning_content || undefined,
        time: nowStr(),
        interrupted: interrupted || undefined,
        // 记录中断终态（stopped/offline/timeout/failed），供"继续生成"入口与同步识别
        status: interrupted ? data.status : undefined,
        modelName: modelsStore.currentModelName,
        thinkingTime: data.thinkingTime
      }
      // 保存token消耗
      if (data.usage) {
        msg.promptTokens = data.usage.prompt_tokens || 0
        msg.completionTokens = data.usage.completion_tokens || 0
        msg.reasoningTokens = data.usage.completion_tokens_details?.reasoning_tokens || 0
        msg.cachedTokens = data.usage.prompt_tokens_details?.cached_tokens || 0
        // 计算增量输入token
        const msgs = chatStore.chats[chatId] || []
        let turnInputTokens = data.usage.prompt_tokens
        for (let j = msgs.length - 1; j >= 0; j--) {
          if (msgs[j].role === 'assistant' && msgs[j].promptTokens) {
            turnInputTokens = data.usage.prompt_tokens - msgs[j].promptTokens
            break
          }
        }
        if (turnInputTokens < 0) turnInputTokens = data.usage.prompt_tokens
        msg.turnInputTokens = turnInputTokens
        // 更新user消息的promptTokens
        for (let i = msgs.length - 1; i >= 0; i--) {
          if (msgs[i].role === 'user') {
            msgs[i].promptTokens = turnInputTokens
            break
          }
        }
      }
      chatStore.addMessage(chatId, msg)
      // 流中途出错（已有部分正文/思考）：正文后追加一条错误气泡说明中断原因
      if (data.error && interrupted) {
        chatStore.addMessage(chatId, makeErrorMsg(data.error))
      }
      streamingMsg.value = null
      regenStreamingIdx.value = -1
      // 首次问答完成后尝试 AI 自动命名（未手动命名时）
      maybeGenerateTitle(chatId)
      nextTick(() => {
        scrollFollow.syncScrollToBottom()
        scrollFollow.updateNavButtons()
      })
    },
    onError: (err) => {
      // 重新生成模式请求级失败：原回答保留不受影响，仅提示，不往会话末尾追加错误气泡
      if (regenTarget) {
        ElMessage.error(err.message || t('chat.unknownError'))
        streamingMsg.value = null
        regenStreamingIdx.value = -1
        return
      }
      // 请求级失败且无有效内容时：统一结算已把可用部分保留在草稿，此处落错误气泡
      chatStore.addMessage(chatId, makeErrorMsg(err.message))
      streamingMsg.value = null
      regenStreamingIdx.value = -1
    }
  })
}

// 统一的错误气泡消息：请求失败/流内错误都以此样式展示，与正常回答区分
function makeErrorMsg(text) {
  return {
    role: 'assistant',
    content: text || t('chat.unknownError'),
    isError: true,
    time: nowStr(),
    modelName: modelsStore.currentModelName
  }
}

function handleStop() {
  streamChat.stop()
}

// 输入框深度思考开关变更时同步本地状态：重新生成/编辑重发/继续生成均读取 isDeepThinking，
// 仅在发送时同步会导致这些不经过输入框发送的流程沿用旧开关状态（切换思考按钮不生效）
function handleThinkingChange(v) {
  isDeepThinking.value = !!v
}

// ===== 上下文占用估算（前端实时提示，与后端保守估算规则一致） =====
// 估算规则：CJK 1 token/字、ASCII 1 token/4 字符、其他 1 token/2 字符，
// 每条消息 +4 结构开销、图片 1100/张、附件按截断上限 500/个，整体 ×1.15 安全系数
function estimateTokensJs(text) {
  if (!text) return 0
  let cjk = 0, ascii = 0, other = 0
  for (const ch of String(text)) {
    const code = ch.codePointAt(0)
    if (code < 128) ascii++
    else if ((code >= 0x3000 && code <= 0x9FFF) || (code >= 0xFF00 && code <= 0xFFEF)) cjk++
    else other++
  }
  return Math.ceil((cjk + ascii / 4 + other / 2) * 1.15)
}

// 当前会话上下文占用估算信息（超出 85% 容量时警示色）
const contextUsageInfo = computed(() => {
  const msgs = chatStore.currentMessages
  if (!msgs.length || !chatStore.isChatHistoryLoaded) return null
  const window_ = modelsStore.currentModelContextWindow
  let used = 0
  // 与后端一致：仅计最后一个“清除上下文”分隔线之后的消息（倒序累计，遇分隔线停止）
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (m.role === 'divider') break
    if (m.role !== 'user' && m.role !== 'assistant') continue
    used += 4 + estimateTokensJs(m.content)
    if (m.images) used += m.images.length * 1100
    if (m.attachments) used += m.attachments.length * 500
  }
  const ratio = used / window_
  const fmtK = (n) => n >= 1000 ? (n / 1000).toFixed(1) + 'K' : String(n)
  return {
    ratio,
    text: t('chat.contextUsage', { used: fmtK(used), total: fmtK(window_) })
  }
})

// ===== 流式草稿恢复与“继续生成”入口 =====
// 刷新/重新打开页面后，若当前会话存在未结算的流式草稿，恢复为一条已标记中断的回答消息；
// 中断的回答提供“重新生成”与“基于已生成内容继续”两个明确操作（继续生成是新的请求，不伪称断点续传）
const draftRestoreTip = ref(null)

// 检查并恢复当前会话的流式草稿（切换会话/刷新后调用）
function restoreStreamDraftIfAny() {
  draftRestoreTip.value = null
  const chatId = chatStore.currentChatId
  if (!chatId) return
  const draft = streamChat.loadDraft(chatId)
  if (!draft) return
  const hasContent = !!(draft.content && String(draft.content).trim())
  const hasReasoning = !!(draft.reasoning_content && String(draft.reasoning_content).trim())
  if (!hasContent && !hasReasoning) {
    streamChat.clearDraft(chatId)
    return
  }
  const msgs = chatStore.chats[chatId] || []
  const last = msgs[msgs.length - 1]
  // 幂等：若最后一条已是该草稿对应的中断消息（内容一致），不重复恢复
  if (last && last.role === 'assistant' && last.interrupted && last.content === draft.content) {
    streamChat.clearDraft(chatId)
    return
  }
  const restored = {
    role: 'assistant',
    content: draft.content || t('chat.answerInterrupted'),
    reasoning_content: draft.reasoning_content || undefined,
    thinkingTime: draft.thinkingTime || undefined,
    time: nowStr(),
    interrupted: true,
    status: draft.status && draft.status !== 'streaming' ? draft.status : 'offline',
    modelName: modelsStore.currentModelName
  }
  if (draft.usage) {
    restored.promptTokens = draft.usage.prompt_tokens || 0
    restored.completionTokens = draft.usage.completion_tokens || 0
    restored.reasoningTokens = draft.usage.completion_tokens_details?.reasoning_tokens || 0
    restored.cachedTokens = draft.usage.prompt_tokens_details?.cached_tokens || 0
  }
  chatStore.addMessage(chatId, restored)
  streamChat.clearDraft(chatId)
  draftRestoreTip.value = { chatId }
  nextTick(() => scrollFollow.scrollToBottomImmediate())
}

// 当前会话最后一条 assistant 消息是否为中断状态（决定“继续生成”操作条是否展示）
const lastInterruptedMsg = computed(() => {
  const msgs = chatStore.currentMessages
  if (!msgs.length || streamChat.isStreaming.value) return null
  const last = msgs[msgs.length - 1]
  return (last && last.role === 'assistant' && last.interrupted) ? last : null
})

// 重新生成中断的回答：不删除中断消息，走版本历史流程为其生成新版本（含二次确认）
async function handleRegenerateInterrupted() {
  const chatId = chatStore.currentChatId
  if (!chatId) return
  const msgs = chatStore.chats[chatId] || []
  // 从末尾定位最后一条中断的 assistant 消息（允许跳过其后的错误气泡）
  let idx = msgs.length - 1
  while (idx >= 0) {
    const m = msgs[idx]
    if (m.role === 'assistant' && m.interrupted) break
    if (m.role === 'assistant' && m.isError) { idx--; continue }
    return
  }
  if (idx < 0) return
  await handleRegenerate(idx - hiddenCount.value)
}

// 基于已生成内容继续：把中断的部分回答保留，追加一条“请继续”用户消息发起新请求。
// 明确这是新请求（上游流无法断点续传），不是恢复原来的流。
async function handleContinueGeneration() {
  const chatId = chatStore.currentChatId
  if (!chatId || streamChat.isStreaming.value) return
  const continueText = t('chat.continuePrompt')
  chatStore.addMessage(chatId, { role: 'user', content: continueText, time: nowStr() })
  chatStore.suspendSync()
  try {
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    await startStream(chatId, isDeepThinking.value)
  } finally {
    chatStore.resumeSync()
  }
}

// 清除上下文：二次确认后向当前会话插入一条分隔线，后续对话不再携带此前历史
async function handleClearContext() {
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return
  }
  const chatId = chatStore.currentChatId
  const msgs = chatStore.chats[chatId] || []
  if (msgs.length === 0) {
    ElMessage.info(t('chat.clearCtxEmpty'))
    return
  }
  // 避免连续插入多条分隔线
  if (msgs[msgs.length - 1].role === 'divider') {
    ElMessage.info(t('chat.ctxCleared'))
    return
  }
  try {
    await ElMessageBox.confirm(t('chat.clearCtxConfirm'), t('chat.clearCtxTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch { return }
  chatStore.addMessage(chatId, { role: 'divider', time: null })
  nextTick(() => scrollFollow.scrollToBottomImmediate())
}

// 首次问答完成后 AI 自动命名会话（仅当仅有一轮问答且未手动命名）
async function maybeGenerateTitle(chatId) {
  const msgs = chatStore.chats[chatId] || []
  const userMsgs = msgs.filter(m => m.role === 'user')
  const assistantMsgs = msgs.filter(m => m.role === 'assistant' && m.content && !m.interrupted && !m.isError)
  if (userMsgs.length !== 1 || assistantMsgs.length < 1) return
  const meta = chatStore.chatMeta[chatId] || {}
  if (meta.title && meta.title.trim()) return
  try {
    const res = await generateChatTitle(modelsStore.currentModelId, userMsgs[0].content, assistantMsgs[0].content)
    if (res && res.success && res.title) {
      chatStore.setAutoTitleIfEmpty(chatId, res.title)
    }
  } catch { /* 失败则保留默认标题 */ }
}

// 重新生成：在当前会话内为该回答生成一个新版本（原回答保留在版本历史中，执行前需用户二次确认）
async function handleRegenerate(idx) {
  // 渲染窗口裁剪后，子组件回传的是展示列表下标，需换算回完整列表下标
  idx += hiddenCount.value
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return
  }
  if (!modelsStore.currentModelId) {
    ElMessage.warning(t('chat.pickModel'))
    return
  }
  const chatId = chatStore.currentChatId
  const msgs = chatStore.chats[chatId] || []
  const target = msgs[idx]
  if (!target || target.role !== 'assistant') return
  // 二次确认：生成新版本，原回答保留在版本历史中可切换查看
  try {
    await ElMessageBox.confirm(t('chat.regenerateConfirm'), t('chat.regenerateTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch { return }
  // 挂起全量同步，新版本写入后再统一上传
  chatStore.suspendSync()
  try {
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    await startStream(chatId, isDeepThinking.value, { regenTarget: { chatId, msgIdx: idx } })
  } finally {
    chatStore.resumeSync()
  }
}

// 版本切换：仅查看历史版本直接切换；若该回答之后已有后续消息，
// 静默切换会让后续对话脱节——提供“仅切换查看”与“从该版本创建分支继续”两种明确选择
async function handleSelectVersion({ absIdx, versionId }) {
  const chatId = chatStore.currentChatId
  if (!chatId) return
  const msgs = chatStore.chats[chatId] || []
  if (!msgs[absIdx]) return
  const hasFollowUps = absIdx < msgs.length - 1
  if (!hasFollowUps) {
    chatStore.selectMessageVersion(chatId, absIdx, versionId)
    return
  }
  // 该回答之后已有对话：切换版本会影响后续上下文，需用户明确选择
  let action = 'cancel'
  try {
    await ElMessageBox.confirm(t('chat.versionSwitchConfirm'), t('chat.versionSwitchTitle'), {
      confirmButtonText: t('chat.versionSwitchBranch'),
      cancelButtonText: t('chat.versionSwitchViewOnly'),
      distinguishCancelAndClose: true,
      type: 'warning'
    })
    action = 'branch'
  } catch (e) {
    if (e === 'cancel') action = 'view'
  }
  if (action === 'branch') {
    // 从选定版本创建分支：复制到该消息为止的内容并切换目标版本，后续对话在分支中进行
    const branchId = chatStore.createBranch(chatId, absIdx)
    if (branchId) {
      chatStore.selectMessageVersion(branchId, absIdx, versionId)
      nextTick(() => scrollFollow.scrollToBottomImmediate())
    }
    return
  }
  if (action === 'view') {
    chatStore.selectMessageVersion(chatId, absIdx, versionId)
  }
}

// 编辑重发：弹窗编辑用户消息，删除该消息及其后所有消息后重新发送
async function handleEditResend(idx) {
  // 同样需将展示列表下标换算回完整列表下标
  idx += hiddenCount.value
  if (streamChat.isStreaming.value) {
    ElMessage.warning(t('chat.waitAnswer'))
    return
  }
  if (!modelsStore.currentModelId) {
    ElMessage.warning(t('chat.pickModel'))
    return
  }
  const sourceChatId = chatStore.currentChatId
  const msg = (chatStore.chats[sourceChatId] || [])[idx]
  if (!msg || msg.role !== 'user') return

  let newText
  try {
    const res = await ElMessageBox.prompt(t('chat.editResendPrompt'), t('chat.editResendTitle'), {
      inputType: 'textarea',
      inputValue: msg.content,
      confirmButtonText: t('chat.resend'),
      cancelButtonText: t('common.cancel'),
      inputValidator: (v) => (v && v.trim()) ? true : t('chat.notEmpty')
    })
    newText = (res.value || '').trim()
  } catch { return }

  // 保留原消息携带的图片与附件
  const images = msg.images && msg.images.length ? msg.images.slice() : null
  const attachments = msg.attachments && msg.attachments.length ? msg.attachments.slice() : null
  const chatId = chatStore.createBranch(sourceChatId, idx - 1)
  if (!chatId) return
  // 同样挂起全量同步，bot 输出结束后再统一上传
  chatStore.suspendSync()
  try {
    const userMsg = { role: 'user', content: newText, time: nowStr() }
    if (images) userMsg.images = images
    if (attachments) userMsg.attachments = attachments
    chatStore.addMessage(chatId, userMsg)
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    await startStream(chatId, isDeepThinking.value)
  } finally {
    chatStore.resumeSync()
  }
}

// 从任意消息位置创建可独立继续对话的会话分支（执行前需用户二次确认）
async function handleCreateBranch(idx) {
  // 二次确认：创建分支会复制当前会话到该消息为止的内容并切换到新会话
  try {
    await ElMessageBox.confirm(t('chat.branchConfirm'), t('chat.branchTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch { return }
  const absoluteIndex = idx + hiddenCount.value
  const branchId = chatStore.createBranch(chatStore.currentChatId, absoluteIndex)
  if (branchId) {
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    ElMessage.success('已创建会话分支，原会话内容保持不变')
  }
}

function copyMsgContent(content) {
  navigator.clipboard.writeText(content).then(() => {
    ElMessage.success(t('chat.copied'))
  }).catch(() => {
    ElMessage.error(t('chat.copyFailed'))
  })
}

function nowStr() {
  return new Date().toLocaleString('zh-CN')
}
</script>
