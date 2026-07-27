<template>
  <aside class="sidebar">
    <button class="sidebar-edge-toggle" @click="$emit('toggle')" aria-label="折叠/展开侧边栏">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
    </button>
    <div class="sidebar-content">
      <div class="sidebar-header">
        <span class="sb-eyebrow">CHATS · 会话</span>
        <button class="icon-btn close-sidebar-btn" @click="$emit('toggle')" title="关闭">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>

      <button class="new-chat-btn" @click="$emit('new-chat')">
        <span class="nc-plus">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
        </span>
        <span class="nc-label">新建会话 · NEW CHAT</span>
        <span class="nc-shortcut">⌘N</span>
      </button>

      <div class="chat-search-box">
        <svg class="chat-search-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" v-model="chatStore.searchKeyword" class="chat-search-input" placeholder="搜索会话..." autocomplete="off" />
        <button v-if="chatStore.searchKeyword" class="chat-search-clear" @click="chatStore.searchKeyword = ''" aria-label="清空">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>

      <div class="chat-list">
        <!-- 加载骨架屏：会话历史未加载完成时显示 -->
        <div v-if="!chatStore.isChatHistoryLoaded" class="chat-list-skeleton">
          <div v-for="n in 5" :key="n" class="chat-skeleton-item"></div>
        </div>
        <template v-for="group in chatStore.sortedChatList.groupOrder" :key="group">
          <div class="chat-date-header">{{ group }}</div>
          <div
            v-for="chat in chatStore.sortedChatList.groups[group]"
            :key="chat.id"
            class="chat-item"
            :class="{ active: chat.id === chatStore.currentChatId }"
            @click="$emit('switch-chat', chat.id)"
          >
            <span class="title">{{ chat.title }}</span>
            <button class="delete-btn" @click.stop="$emit('delete-chat', chat.id)" title="删除">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/></svg>
            </button>
          </div>
        </template>
        <div v-if="chatStore.isChatHistoryLoaded && chatStore.searchKeyword && chatStore.sortedChatList.total === 0" class="chat-search-empty">
          未找到匹配的会话
        </div>
      </div>

      <div class="sidebar-footer">
        <div class="user-info" @click.stop="toggleUserMenu" title="点击展开菜单">
          <div class="user-avatar-wrap">
            <img class="avatar-icon" :src="userAvatarSrc" style="width:14px;height:14px;border-radius:50%" />
          </div>
          <span>{{ authStore.username || '用户' }}</span>
          <span class="user-info-spacer"></span>
          <span class="user-info-more">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg>
          </span>

          <!-- 用户菜单 -->
          <div class="user-menu" v-show="userMenuOpen" @click.stop>
            <div class="user-menu-item" @click="$emit('open-settings'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
              <span>个人设置</span>
            </div>
            <div class="user-menu-item" @click="$emit('open-about'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
              <span>关于</span>
            </div>
            <div class="user-menu-divider"></div>
            <div class="user-menu-item" @click="$emit('open-stats'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/><line x1="3" y1="20" x2="21" y2="20"/></svg>
              <span>数据统计</span>
            </div>
            <div v-if="authStore.role === 'admin'" class="user-menu-item" @click="goAdmin">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" fill-rule="evenodd"><path d="M12 8.4A3.6 3.6 0 1 0 12 15.6 3.6 3.6 0 1 0 12 8.4Z M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58Z"/></svg>
              <span>后台管理</span>
            </div>
            <div class="user-menu-divider"></div>
            <div class="user-menu-item user-menu-item-danger" @click="$emit('logout'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
              <span>退出登录</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'

const router = useRouter()
const chatStore = useChatStore()
const authStore = useAuthStore()
const { getTheme } = useTheme()

const userMenuOpen = ref(false)

const userAvatarSrc = computed(() => {
  return getTheme() === 'dark' ? '/icons/user_ss.svg' : '/icons/user.svg'
})

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
}

function goAdmin() {
  userMenuOpen.value = false
  router.push('/admin')
}

function closeMenuOnOutside(e) {
  if (userMenuOpen.value) {
    userMenuOpen.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', closeMenuOnOutside)
})

onUnmounted(() => {
  document.removeEventListener('click', closeMenuOnOutside)
})

defineEmits(['toggle', 'new-chat', 'switch-chat', 'delete-chat', 'open-settings', 'open-about', 'open-stats', 'logout'])
</script>
