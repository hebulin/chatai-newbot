<template>
  <div class="chat-page">
    <div class="paper-grain" aria-hidden="true"></div>

    <!-- 顶栏 -->
    <header class="atelier-top">
      <div class="top-left">
        <button class="icon-btn toggle-sidebar-btn" @click="toggleSidebar" aria-label="侧栏">
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
        <button class="icon-btn share-chat-btn" @click="handleShareChat" title="分享当前会话" aria-label="分享">
          <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
        </button>
        <button class="theme-toggle-btn" @click="toggleTheme" title="切换主题" aria-label="主题">
          <span class="toggle-track"><span class="toggle-knob"></span></span>
        </button>
      </div>
    </header>

    <!-- 侧边栏 -->
    <ChatSidebar
      :class="{ open: sidebarOpen, collapsed: sidebarCollapsed }"
      @toggle="toggleSidebar"
      @new-chat="handleNewChat"
      @switch-chat="handleSwitchChat"
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
      <div class="chat-viewport">
        <div class="chat-container" ref="chatContainerRef">
          <ChatMessages
            :messages="chatStore.currentMessages"
            :is-streaming="streamChat.isStreaming.value"
            :streaming-msg="streamingMsg"
            @copy="copyMsgContent"
            @lightbox="lightboxSrc = $event"
            @regenerate="handleRegenerate"
            @edit-resend="handleEditResend"
          />
        </div>

        <!-- 滚动导航（对话区域右下角，不遮挡输入框） -->
        <div class="scroll-nav" v-show="scrollFollow.showScrollToBottom.value">
          <button class="scroll-nav-btn" @click="scrollFollow.userScrollToBottom()" title="回到底部">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
        </div>
      </div>

      <!-- 当前会话同步提示（同步超过 3 秒才显示） -->
      <div v-if="syncTipVisible" class="chat-sync-tip">当前会话记录同步中…</div>

      <!-- 输入区 -->
      <ChatInput
        :is-streaming="streamChat.isStreaming.value"
        :supports-thinking="modelsStore.currentModelSupportsThinking"
        :supports-multimodal="modelsStore.currentModelSupportsMultimodal"
        @send="handleSend"
        @stop="handleStop"
        @clear-context="handleClearContext"
      />
    </main>

    <!-- 设置弹窗 -->
    <SettingsModal v-if="showSettings" @close="showSettings = false" @logout="handleLogout" />

    <!-- 关于弹窗 -->
    <AboutModal v-if="showAbout" @close="showAbout = false" />

    <!-- 数据统计弹窗 -->
    <UsageStatsModal v-if="showStats" @close="showStats = false" />

    <!-- 图片灯箱 -->
    <ImageLightbox v-if="lightboxSrc" :src="lightboxSrc" @close="lightboxSrc = null" />
  </div>
</template>

<script setup>
import '@/styles/chat.css'
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useChatStore } from '@/stores/chat'
import { useModelsStore } from '@/stores/models'
import { useAuthStore } from '@/stores/auth'
import { useStreamChat } from '@/composables/useStreamChat'
import { useScrollFollow } from '@/composables/useScrollFollow'
import { useTheme } from '@/composables/useTheme'
import { logout as apiLogout } from '@/api/auth'
import { saveChatHistory, generateChatTitle } from '@/api/chat'
import { createShare } from '@/api/share'
import ChatSidebar from '@/components/chat/ChatSidebar.vue'
import ChatMessages from '@/components/chat/ChatMessages.vue'
import ChatInput from '@/components/chat/ChatInput.vue'
import SettingsModal from '@/components/chat/SettingsModal.vue'
import AboutModal from '@/components/chat/AboutModal.vue'
import UsageStatsModal from '@/components/chat/UsageStatsModal.vue'
import ImageLightbox from '@/components/chat/ImageLightbox.vue'

const router = useRouter()
const chatStore = useChatStore()
const modelsStore = useModelsStore()
const authStore = useAuthStore()
const streamChat = useStreamChat()
const { toggleTheme, getTheme } = useTheme()

const chatContainerRef = ref(null)
const scrollFollow = useScrollFollow(chatContainerRef)

const sidebarOpen = ref(false)
const sidebarCollapsed = ref(false)
const isMobile = ref(window.innerWidth <= 768)
const showSettings = ref(false)
const showAbout = ref(false)
const showStats = ref(false)
const lightboxSrc = ref(null)
const isDeepThinking = ref(false)
const pendingImages = ref([])

const streamingMsg = ref(null)
const syncTipVisible = ref(false)

const brandIconSrc = computed(() => {
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
  return '新会话 · NEW'
})

onMounted(async () => {
  // 初始化
  if (window.innerWidth <= 768) {
    sidebarCollapsed.value = true
  }
  await chatStore.loadFromServer()
  await modelsStore.loadModels()
  await nextTick()
  scrollFollow.init()

  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  scrollFollow.unbindEvents()
})

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

// 分享指定会话：先选择有效期，再强制同步会话到服务端，最后生成只读分享链接并复制
async function doShare(chatId) {
  const msgs = chatStore.chats[chatId] || []
  if (!msgs.some(m => m.role === 'user')) {
    ElMessage.info('该会话还没有内容，无法分享')
    return
  }
  let expireDays = 0
  try {
    const { value } = await ElMessageBox.prompt('设置分享链接有效期（天）。0 或留空表示永久有效', '分享会话', {
      confirmButtonText: '生成链接',
      cancelButtonText: '取消',
      inputValue: '0',
      inputValidator: (v) => {
        if (v === '' || v == null) return true
        return /^\d+$/.test(String(v).trim()) || '请输入非负整数'
      }
    })
    const n = parseInt(String(value || '0').trim(), 10)
    expireDays = isNaN(n) ? 0 : n
  } catch { return }
  try {
    // 绕过 500ms 防抖，确保服务端已持有最新会话
    await saveChatHistory({
      lastChatId: chatStore.currentChatId,
      chats: chatStore.chats,
      chatMeta: chatStore.chatMeta,
      deletedChatIds: chatStore.deletedChatIds
    })
    const res = await createShare(chatId, expireDays)
    if (res && res.success) {
      const url = location.origin + '/share/' + res.data.id
      let copied = false
      try {
        await navigator.clipboard.writeText(url)
        copied = true
      } catch (e) { /* 非 https 环境剪贴板可能不可用 */ }
      const expiryTip = res.data.expiresAt ? ('\n有效期至：' + res.data.expiresAt) : '\n永久有效'
      ElMessageBox.alert(url + expiryTip, '分享链接已生成' + (copied ? '（已复制到剪贴板）' : ''), {
        confirmButtonText: '知道了',
        dangerouslyUseHTMLString: false
      })
    } else {
      ElMessage.error((res && res.message) || '分享失败')
    }
  } catch (e) {
    // 异常提示已由 request 拦截器统一处理
  }
}

function handleNewChat() {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('请等待回答完成')
    return
  }
  // 如果当前会话为空，不再新建
  const msgs = chatStore.currentMessages
  if (msgs.length > 0 && msgs.some(m => m.role === 'user')) {
    chatStore.newChat()
    modelsStore.applyDefaultModel()
    isDeepThinking.value = false
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  } else {
    ElMessage.info('当前已是新会话')
  }
}

function handleSwitchChat(id) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('请等待回答完成')
    return
  }
  chatStore.switchChat(id)
  if (isMobile.value) {
    sidebarOpen.value = false
  }
  nextTick(() => scrollFollow.scrollToBottomImmediate())
}

function handleDeleteChat(id) {
  ElMessageBox.confirm('确定删除该会话？', '删除会话', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    chatStore.deleteChat(id)
    nextTick(() => scrollFollow.scrollToBottomImmediate())
  }).catch(() => {})
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning'
    })
    apiLogout().catch(() => {})
    authStore.logout()
    router.push('/login')
  } catch (e) { /* cancelled */ }
}

async function handleSend({ text, images, deepThinking }) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('当前还有内容没回答完，请点击右侧停止按钮中断')
    return
  }
  if (!text && (!images || images.length === 0)) return
  if (!modelsStore.currentModelId) {
    ElMessage.warning('请先选择模型')
    return
  }

  isDeepThinking.value = deepThinking
  const chatId = chatStore.currentChatId

  // 挂起全量同步：发送阶段只做当前会话同步，全量上传延后到 bot 输出结束
  chatStore.suspendSync()
  try {
    // 添加用户消息（本地先渲染）
    const userMsg = { role: 'user', content: text || '(图片)', time: nowStr() }
    if (images && images.length > 0) {
      userMsg.images = images.slice()
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
async function startStream(chatId, deepThinking) {
  // 只携带最后一个“清除上下文”分隔线之后的消息
  const source = chatStore.chats[chatId] || []
  let startIdx = 0
  for (let i = source.length - 1; i >= 0; i--) {
    if (source[i].role === 'divider') { startIdx = i + 1; break }
  }
  const messages = source.slice(startIdx)
    .filter(m => m.role === 'user' || (m.role === 'assistant' && m.content && m.content.trim()))
    .map(m => {
      if (m.role === 'user' && m.images && m.images.length > 0) {
        return { role: m.role, content: m.content, images: m.images }
      }
      return { role: m.role, content: m.content }
    })

  const requestBody = {
    modelConfigId: modelsStore.currentModelId,
    messages,
    stream: true,
    deepThinking,
    temperature: 0.7
  }

  streamingMsg.value = { role: 'assistant', content: '', reasoning_content: '', time: null, modelName: modelsStore.currentModelName }

  await streamChat.send(requestBody, {
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
      const content = data.content || (data.interrupted ? '（回答已中断）' : '（无正式回答）')
      const msg = {
        role: 'assistant',
        content,
        reasoning_content: data.reasoning_content || undefined,
        time: nowStr(),
        interrupted: data.interrupted || undefined,
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
      streamingMsg.value = null
      // 首次问答完成后尝试 AI 自动命名（未手动命名时）
      maybeGenerateTitle(chatId)
      nextTick(() => {
        scrollFollow.syncScrollToBottom()
        scrollFollow.updateNavButtons()
      })
    },
    onError: (err) => {
      const msg = { role: 'assistant', content: '❌ ' + err.message, time: nowStr() }
      chatStore.addMessage(chatId, msg)
      streamingMsg.value = null
    }
  })
}

function handleStop() {
  streamChat.stop()
}

// 清除上下文：向当前会话插入一条分隔线，后续对话不再携带此前历史
function handleClearContext() {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('请等待回答完成')
    return
  }
  const chatId = chatStore.currentChatId
  const msgs = chatStore.chats[chatId] || []
  if (msgs.length === 0) {
    ElMessage.info('当前会话为空，无需清除')
    return
  }
  // 避免连续插入多条分隔线
  if (msgs[msgs.length - 1].role === 'divider') {
    ElMessage.info('上下文已清除')
    return
  }
  chatStore.addMessage(chatId, { role: 'divider', time: null })
  nextTick(() => scrollFollow.scrollToBottomImmediate())
}

// 首次问答完成后 AI 自动命名会话（仅当仅有一轮问答且未手动命名）
async function maybeGenerateTitle(chatId) {
  const msgs = chatStore.chats[chatId] || []
  const userMsgs = msgs.filter(m => m.role === 'user')
  const assistantMsgs = msgs.filter(m => m.role === 'assistant' && m.content && !m.interrupted)
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

// 重新生成：删除最后一条 AI 回复，基于其前的历史重新请求
async function handleRegenerate(idx) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('请等待回答完成')
    return
  }
  if (!modelsStore.currentModelId) {
    ElMessage.warning('请先选择模型')
    return
  }
  const chatId = chatStore.currentChatId
  // 同样挂起全量同步，bot 输出结束后再统一上传（截断+新回复一次性同步）
  chatStore.suspendSync()
  try {
    chatStore.truncateMessages(chatId, idx)
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    await startStream(chatId, isDeepThinking.value)
  } finally {
    chatStore.resumeSync()
  }
}

// 编辑重发：弹窗编辑用户消息，删除该消息及其后所有消息后重新发送
async function handleEditResend(idx) {
  if (streamChat.isStreaming.value) {
    ElMessage.warning('请等待回答完成')
    return
  }
  if (!modelsStore.currentModelId) {
    ElMessage.warning('请先选择模型')
    return
  }
  const chatId = chatStore.currentChatId
  const msg = (chatStore.chats[chatId] || [])[idx]
  if (!msg || msg.role !== 'user') return

  let newText
  try {
    const res = await ElMessageBox.prompt('确认后将删除该消息及其后的所有回复，并重新发送', '编辑重发', {
      inputType: 'textarea',
      inputValue: msg.content,
      confirmButtonText: '重新发送',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim()) ? true : '内容不能为空'
    })
    newText = (res.value || '').trim()
  } catch { return }

  // 保留原消息携带的图片
  const images = msg.images && msg.images.length ? msg.images.slice() : null
  // 同样挂起全量同步，bot 输出结束后再统一上传
  chatStore.suspendSync()
  try {
    chatStore.truncateMessages(chatId, idx)
    const userMsg = { role: 'user', content: newText, time: nowStr() }
    if (images) userMsg.images = images
    chatStore.addMessage(chatId, userMsg)
    nextTick(() => scrollFollow.scrollToBottomImmediate())
    await startStream(chatId, isDeepThinking.value)
  } finally {
    chatStore.resumeSync()
  }
}

function copyMsgContent(content) {
  navigator.clipboard.writeText(content).then(() => {
    ElMessage.success('已复制')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

function nowStr() {
  return new Date().toLocaleString('zh-CN')
}
</script>
