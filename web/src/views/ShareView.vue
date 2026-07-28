<template>
  <div class="share-page">
    <!-- 顶栏 -->
    <header class="share-top">
      <div class="share-brand">
        <img class="share-brand-icon" :src="aiAvatarSrc" alt="AI" />
        <span class="share-brand-name">Atelier · 会话分享</span>
      </div>
      <a class="share-go-home" href="/">进入工作台</a>
    </header>

    <!-- 加载中 -->
    <div v-if="loading" class="share-status">加载中...</div>

    <!-- 错误提示 -->
    <div v-else-if="errorMsg" class="share-status share-error">
      <p>{{ errorMsg }}</p>
      <a href="/">返回首页</a>
    </div>

    <!-- 分享内容 -->
    <template v-else>
      <div class="share-meta">
        <h1 class="share-title">{{ title }}</h1>
        <p class="share-sub">由 {{ sharedBy }} 分享 · {{ sharedAt }} · 只读快照<span v-if="expiresAt"> · 有效期至 {{ expiresAt }}</span></p>
      </div>

      <div class="chat-messages share-messages" ref="containerRef">
        <template v-for="(msg, idx) in messages" :key="idx">
          <div v-if="msg.role === 'divider'" class="context-divider">
            <span class="context-divider-label">以上上下文已清除</span>
          </div>
          <div v-else class="msg-wrapper" :class="msg.role">
          <div v-if="msg.time" class="msg-time-top">{{ msg.time }}</div>
          <div class="msg-row">
            <div class="msg-avatar" :class="msg.role === 'user' ? 'user-av' : 'ai-av'">
              <img :src="msg.role === 'user' ? userAvatarSrc : aiAvatarSrc" style="width:100%;height:100%;border-radius:10px;object-fit:cover" />
            </div>
            <div class="msg-bubble">
              <template v-if="msg.role === 'user'">
                <div v-if="msg.images && msg.images.length" class="user-msg-images">
                  <img v-for="(img, i) in msg.images" :key="i" class="user-msg-img" :src="img" alt="发送的图片" />
                </div>
                <span v-html="formatUserContent(msg.content)"></span>
              </template>
              <template v-else>
                <div v-if="msg.reasoning_content" class="thinking-block collapsed">
                  <div class="thinking-header" @click="toggleThinking($event)">
                    <span class="arrow">▼</span>
                    {{ msg.thinkingTime ? '已思考（用时 ' + msg.thinkingTime + ' 秒）' : '已思考' }}
                  </div>
                  <div class="thinking-body" v-html="renderMarkdown(msg.reasoning_content)"></div>
                </div>
                <div v-if="msg.content" class="answer-content" v-html="renderMarkdown(msg.content)"></div>
              </template>
            </div>
          </div>
          <div class="msg-footer">
            <span v-if="msg.role === 'assistant' && msg.modelName" class="msg-model-name">{{ msg.modelName }}</span>
          </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { getSharedChat } from '@/api/share'
import { renderMarkdown, escapeHtml, processSpecialContent, renderMermaidBlocks, handleMermaidToolbarClick } from '@/composables/useMarkdown'
import { useTheme } from '@/composables/useTheme'
import '@/styles/chat.css'

const route = useRoute()
const { getTheme } = useTheme()

const loading = ref(true)
const errorMsg = ref('')
const title = ref('')
const sharedBy = ref('')
const sharedAt = ref('')
const expiresAt = ref('')
const messages = ref([])
const containerRef = ref(null)

const userAvatarSrc = computed(() => getTheme() === 'dark' ? '/icons/user_ss.svg' : '/icons/user.svg')
const aiAvatarSrc = computed(() => getTheme() === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg')

function formatUserContent(content) {
  return escapeHtml(content || '').replace(/\n/g, '<br>')
}

function toggleThinking(e) {
  e.currentTarget.parentElement.classList.toggle('collapsed')
}

onMounted(async () => {
  try {
    const res = await getSharedChat(route.params.id)
    if (res && res.success) {
      title.value = res.title || '分享的会话'
      sharedBy.value = res.sharedBy || ''
      sharedAt.value = res.sharedAt || ''
      expiresAt.value = res.expiresAt || ''
      messages.value = res.messages || []
      document.title = title.value + ' - 会话分享'
      // 先退出 loading 让 v-else 分支渲染出 containerRef，再处理代码高亮与 mermaid 图表；
      // 否则 containerRef 为 null，mermaid 渲染被整体跳过，图表永远停在“渲染中”占位
      loading.value = false
      await nextTick()
      if (containerRef.value) {
        processSpecialContent(containerRef.value)
        renderMermaidBlocks(containerRef.value, getTheme())
        containerRef.value.addEventListener('click', handleMermaidToolbarClick)
      }
    } else {
      errorMsg.value = (res && res.message) || '分享内容加载失败'
    }
  } catch (e) {
    errorMsg.value = '分享内容加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* chat.css 全局锁死了 html/body 的滚动（overflow:hidden，聊天页自行管理滚动），
   分享页同样引入了该 CSS，故这里让 .share-page 自己作为滚动容器，
   否则内容超出视口后无法下滑 */
.share-page {
  height: 100vh;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  background: var(--bg, #f7f6f3);
  color: var(--ink, #0e1115);
}
@supports (height: 100dvh) {
  .share-page { height: 100dvh; }
}

.share-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 24px;
  border-bottom: 1px solid rgba(128, 128, 128, 0.2);
  position: sticky;
  top: 0;
  background: var(--bg, #f7f6f3);
  z-index: 10;
}

.share-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.share-brand-icon {
  width: 28px;
  height: 28px;
  border-radius: 8px;
}

.share-brand-name {
  font-weight: 600;
  font-size: 15px;
}

.share-go-home {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
}

.share-go-home:hover {
  color: #374151;
  text-decoration: underline;
}

.share-status {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #6b7280;
  font-size: 15px;
  padding: 60px 20px;
}

.share-error a {
  color: #4f46e5;
}

.share-meta {
  max-width: 860px;
  width: 100%;
  margin: 24px auto 0;
  padding: 0 20px;
}

.share-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0 0 6px;
}

.share-sub {
  font-size: 13px;
  color: #9ca3af;
  margin: 0;
}

.share-messages {
  max-width: 860px;
  width: 100%;
  margin: 12px auto 40px;
  padding: 0 20px;
}

/* 上下文清除分隔线 */
.context-divider {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px auto;
  max-width: 760px;
  color: #9ca3af;
  font-size: 12px;
}
.context-divider::before,
.context-divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: rgba(128, 128, 128, 0.2);
}
.context-divider-label {
  flex-shrink: 0;
  padding: 2px 10px;
  border-radius: 10px;
  background: rgba(128, 128, 128, 0.08);
  white-space: nowrap;
}
</style>
