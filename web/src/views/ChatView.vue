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
          />
        </div>

        <!-- 滚动导航（对话区域右下角，不遮挡输入框） -->
        <div class="scroll-nav" v-show="scrollFollow.showScrollToBottom.value">
          <button class="scroll-nav-btn" @click="scrollFollow.userScrollToBottom()" title="回到底部">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="6 9 12 15 18 9"/></svg>
          </button>
        </div>
      </div>

      <!-- 输入区 -->
      <ChatInput
        :is-streaming="streamChat.isStreaming.value"
        :supports-thinking="modelsStore.currentModelSupportsThinking"
        :supports-multimodal="modelsStore.currentModelSupportsMultimodal"
        @send="handleSend"
        @stop="handleStop"
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

const brandIconSrc = computed(() => {
  const theme = getTheme()
  return theme === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg'
})

const chatTitle = computed(() => {
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

  // 添加用户消息
  const userMsg = { role: 'user', content: text || '(图片)', time: nowStr() }
  if (images && images.length > 0) {
    userMsg.images = images.slice()
  }
  chatStore.addMessage(chatId, userMsg)

  nextTick(() => scrollFollow.scrollToBottomImmediate())

  // 准备请求
  const messages = chatStore.chats[chatId]
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
