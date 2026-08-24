<template>
  <aside class="sidebar">
    <button class="sidebar-edge-toggle" @click="$emit('toggle')" :aria-label="t('sidebar.toggleAria')">
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 18 9 12 15 6"/></svg>
    </button>
    <div class="sidebar-content">
      <div class="sidebar-header">
        <span class="sb-eyebrow">{{ t('sidebar.eyebrow') }}</span>
        <div class="sidebar-header-actions">
          <button class="sidebar-header-action" :class="{ active: multiSelectMode }" @click="toggleMultiSelect" :title="t('sidebar.multiSelect')" :aria-label="t('sidebar.multiSelect')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><rect x="3" y="4" width="5" height="5" rx="1"/><rect x="3" y="15" width="5" height="5" rx="1"/><path d="M12 6h9M12 17h9"/><path d="m4.5 6.5 1 1 2-2"/></svg>
          </button>
          <button class="icon-btn close-sidebar-btn" @click="$emit('toggle')" :title="t('common.close')" :aria-label="t('common.close')">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
      </div>

      <div class="new-chat-row">
        <button class="new-chat-btn" @click="$emit('new-chat')">
          <span class="nc-plus">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
          </span>
          <span class="nc-label">{{ t('sidebar.newChat') }}</span>
          <span class="nc-shortcut">⌘N</span>
        </button>
      </div>

      <div v-if="multiSelectMode" class="multi-select-toolbar" aria-live="polite">
        <span>已选 {{ selectedChatIds.length }} 项</span>
        <button type="button" :disabled="!selectedChatIds.length" @click="bulkMove">移动</button>
        <button type="button" class="danger" :disabled="!selectedChatIds.length" @click="bulkDelete">删除</button>
        <button type="button" @click="toggleMultiSelect">完成</button>
      </div>

      <div class="chat-search-box">
        <svg class="chat-search-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input type="text" v-model="chatStore.searchKeyword" class="chat-search-input" :placeholder="t('sidebar.searchPlaceholder')" autocomplete="off" />
        <button v-if="chatStore.searchKeyword" class="chat-search-clear" @click="chatStore.searchKeyword = ''" :aria-label="t('sidebar.clear')">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>

      <div class="chat-list" :class="{ 'is-scrolling': listScrolling }" @scroll.passive="onChatListScroll">
        <!-- 加载骨架屏：会话历史未加载完成时显示 -->
        <div v-if="!chatStore.isChatHistoryLoaded" class="chat-list-skeleton">
          <div v-for="n in 5" :key="n" class="chat-skeleton-item"></div>
        </div>
        <div v-if="chatStore.isChatHistoryLoaded && !chatStore.searchKeyword" class="chat-date-header folder-section-title">
          <span>{{ t('sidebar.folders') }}</span>
          <button class="folder-title-action" @click="onCreateFolder" :title="t('sidebar.newFolder')" :aria-label="t('sidebar.newFolder')">
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/><line x1="12" y1="10" x2="12" y2="16"/><line x1="9" y1="13" x2="15" y2="13"/></svg>
          </button>
        </div>
        <!-- 全文搜索结果（服务端检索消息内容） -->
        <template v-if="chatStore.searchKeyword && searchResults.length">
          <div class="chat-date-header">{{ t('sidebar.messageMatch') }} · {{ searchResults.length }}</div>
          <div
            v-for="(r, i) in searchResults"
            :key="'hit-' + i"
            class="chat-item search-hit"
            :class="{ active: r.chatId === chatStore.currentChatId }"
            @click="$emit('switch-chat', r.chatId)"
          >
            <div class="search-hit-body">
              <span class="title">{{ r.chatTitle }}</span>
              <span class="search-hit-snippet">{{ (r.role === 'user' ? t('sidebar.mePrefix') : t('sidebar.aiPrefix')) + r.snippet }}</span>
            </div>
          </div>
        </template>
        <template v-for="(row, rIdx) in renderRows" :key="row.type + '-' + (row.id || rIdx)">
          <!-- 日期分组标题 -->
          <div v-if="row.type === 'dateHeader'" class="chat-date-header">{{ row.label }}</div>

          <!-- 文件夹分组标题 -->
          <div
            v-else-if="row.type === 'folderHeader'"
            class="chat-folder-header"
            @click="chatStore.toggleFolderCollapsed(row.folder.id)"
          >
            <svg class="folder-caret" :class="{ collapsed: row.folder.collapsed }" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="6 9 12 15 18 9"/></svg>
            <svg class="folder-icon" width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"/></svg>
            <span class="folder-name">{{ row.folder.name }}</span>
            <span class="folder-count">{{ row.count }}</span>
            <button class="chat-more-btn folder-more-btn" @click.stop="toggleFolderMenu(row.folder.id)" :title="t('sidebar.more')" :aria-label="t('sidebar.more')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>
            </button>
            <div class="chat-item-menu folder-menu" v-if="openFolderMenuId === row.folder.id" @click.stop>
              <div class="chat-item-menu-item" @click="onRenameFolder(row.folder)">{{ t('sidebar.rename') }}</div>
              <div class="chat-item-menu-item chat-item-menu-item-danger" @click="onDeleteFolder(row.folder)">{{ t('sidebar.deleteFolder') }}</div>
            </div>
          </div>

          <!-- 会话项 -->
          <div
            v-else
            class="chat-item"
            :class="{ active: row.chat.id === chatStore.currentChatId, selected: selectedChatIds.includes(row.chat.id), 'in-folder': row.inFolder }"
            role="button" tabindex="0"
            @click="handleChatClick(row.chat.id)"
            @keydown.enter.prevent="handleChatClick(row.chat.id)"
          >
            <input v-if="multiSelectMode" class="chat-select-checkbox" type="checkbox" :checked="selectedChatIds.includes(row.chat.id)" :aria-label="'选择会话 ' + row.chat.title" @click.stop="toggleChatSelection(row.chat.id)" />
            <svg v-if="row.chat.pinned" class="pin-marker" width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><path d="M16 3l5 5-3 1-4 4-1 6-2-2-4 4-1-1 4-4-2-2 6-1 4-4z"/></svg>
            <span class="title">{{ row.chat.title }}</span>
            <button v-if="!multiSelectMode" class="chat-more-btn" @click.stop="toggleChatMenu(row.chat.id)" :title="t('sidebar.more')" :aria-label="t('sidebar.more')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="19" cy="12" r="1.6"/></svg>
            </button>
            <div class="chat-item-menu" :class="{ 'is-move-menu': moveMenuId === row.chat.id }" v-if="openMenuId === row.chat.id" @click.stop>
              <!-- 移动到文件夹的目标选择态 -->
              <template v-if="moveMenuId === row.chat.id">
                <div class="chat-item-menu-title">{{ t('sidebar.moveToFolder') }}</div>
                <div class="chat-item-menu-item" v-for="f in chatStore.folders" :key="f.id" @click="moveToFolder(row.chat.id, f.id)">
                  <span class="move-check">{{ currentFolderId(row.chat.id) === f.id ? '✓' : '' }}</span>
                  <span class="move-name">{{ f.name }}</span>
                </div>
                <div class="chat-item-menu-empty" v-if="chatStore.folders.length === 0">{{ t('sidebar.noFolders') }}</div>
                <div class="chat-item-menu-divider"></div>
                <div class="chat-item-menu-item" @click="newFolderAndMove(row.chat.id)">{{ t('sidebar.newFolderInline') }}</div>
                <div class="chat-item-menu-item chat-item-menu-item-muted" @click="backFromMoveMenu(row.chat.id)">{{ t('sidebar.back') }}</div>
              </template>
              <!-- 常规操作菜单 -->
              <template v-else>
                <div class="chat-item-menu-item" @click="onTogglePin(row.chat.id)">{{ row.chat.pinned ? t('sidebar.unpin') : t('sidebar.pin') }}</div>
                <div class="chat-item-menu-item" @click="startMoveMenu(row.chat.id)">{{ t('sidebar.moveToFolder') }}</div>
                <div class="chat-item-menu-item" v-if="currentFolderId(row.chat.id)" @click="moveToFolder(row.chat.id, null)">{{ t('sidebar.removeFromFolder') }}</div>
                <div class="chat-item-menu-item" @click="onRename(row.chat)">{{ t('sidebar.rename') }}</div>
                <div class="chat-item-menu-item" @click="onShare(row.chat.id)">{{ t('sidebar.share') }}</div>
                <div class="chat-item-menu-item" @click="onExport(row.chat.id)">{{ t('sidebar.exportMarkdown') }}</div>
                <div class="chat-item-menu-item chat-item-menu-item-danger" @click="onDelete(row.chat.id)">{{ t('sidebar.delete') }}</div>
              </template>
            </div>
          </div>
        </template>
        <div v-if="chatStore.isChatHistoryLoaded && chatStore.searchKeyword && !searching && chatStore.sortedChatList.total === 0 && searchResults.length === 0" class="chat-search-empty">
          {{ t('sidebar.noMatch') }}
        </div>
      </div>

      <div class="sidebar-footer">
        <div class="user-info" @click.stop="toggleUserMenu" :title="t('sidebar.clickMenu')">
          <div class="user-avatar-wrap">
            <img class="avatar-icon" :src="userAvatarSrc" style="width:14px;height:14px;border-radius:50%" />
          </div>
          <span>{{ userProfile.displayName || authStore.username || t('sidebar.user') }}</span>
          <span class="user-info-spacer"></span>
          <span class="user-info-more">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="19" cy="12" r="1.5"/></svg>
          </span>

          <!-- 用户菜单 -->
          <div class="user-menu" v-show="userMenuOpen" @click.stop>
            <div class="user-menu-item" @click="$emit('open-settings'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
              <span>{{ t('sidebar.settings') }}</span>
            </div>
            <div class="user-menu-item" @click="$emit('open-about'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
              <span>{{ t('sidebar.about') }}</span>
            </div>
            <div class="user-menu-divider"></div>
            <div class="user-menu-item" @click="$emit('open-stats'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="18" y1="20" x2="18" y2="10"/><line x1="12" y1="20" x2="12" y2="4"/><line x1="6" y1="20" x2="6" y2="14"/><line x1="3" y1="20" x2="21" y2="20"/></svg>
              <span>{{ t('sidebar.stats') }}</span>
            </div>
            <div v-if="authStore.role === 'admin'" class="user-menu-item" @click="goAdmin" @mouseenter="prefetchAdminEntry">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="currentColor" fill-rule="evenodd"><path d="M12 8.4A3.6 3.6 0 1 0 12 15.6 3.6 3.6 0 1 0 12 8.4Z M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58Z"/></svg>
              <span>{{ t('sidebar.admin') }}</span>
            </div>
            <div class="user-menu-divider"></div>
            <div class="user-menu-item user-menu-item-danger" @click="$emit('logout'); userMenuOpen = false">
              <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><polyline points="16 17 21 12 16 7"/><line x1="21" y1="12" x2="9" y2="12"/></svg>
              <span>{{ t('sidebar.logout') }}</span>
            </div>
          </div>
        </div>
        <!-- 版本号展示 -->
        <div class="sidebar-version">v{{ APP_VERSION }}</div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessageBox } from 'element-plus'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { searchChatHistory } from '@/api/chat'
import { getUserProfile } from '@/api/user'
import { APP_VERSION } from '@/config/version'

const router = useRouter()
const { t } = useI18n()
const chatStore = useChatStore()
const authStore = useAuthStore()
const { getTheme } = useTheme()

const userMenuOpen = ref(false)
const multiSelectMode = ref(false)
const selectedChatIds = ref([])
const userProfile = ref({ displayName: '', avatarType: 'default', avatarValue: '' })

// 会话列表滚动态：滚动时临时显示滚动条，停止后自动隐藏
const listScrolling = ref(false)
let scrollHideTimer = null

function onChatListScroll() {
  listScrolling.value = true
  if (scrollHideTimer) clearTimeout(scrollHideTimer)
  scrollHideTimer = setTimeout(() => { listScrolling.value = false }, 800)
}

// 将会话列表（文件夹分组 + 日期分组）平铺为统一的渲染行序列，
// 避免会话项模板在文件夹区与日期区重复定义
const renderRows = computed(() => {
  const rows = []
  const list = chatStore.sortedChatList
  list.folderGroups.forEach(fg => {
    rows.push({ type: 'folderHeader', id: fg.folder.id, folder: fg.folder, count: fg.chats.length })
    if (!fg.folder.collapsed) {
      fg.chats.forEach(c => rows.push({ type: 'chat', id: c.id, chat: c, inFolder: true }))
    }
  })
  list.groupOrder.forEach(label => {
    rows.push({ type: 'dateHeader', label })
    ;(list.groups[label] || []).forEach(c => rows.push({ type: 'chat', id: c.id, chat: c, inFolder: false }))
  })
  return rows
})

// 会话项操作菜单（置顶/移动文件夹/重命名/分享/导出/删除）
const openMenuId = ref(null)
// 处于"移动到文件夹"目标选择态的会话 id
const moveMenuId = ref(null)
// 文件夹标题行操作菜单（重命名/删除）
const openFolderMenuId = ref(null)

function toggleChatMenu(id) {
  moveMenuId.value = null
  openFolderMenuId.value = null
  openMenuId.value = openMenuId.value === id ? null : id
}

function onTogglePin(id) {
  chatStore.togglePin(id)
  openMenuId.value = null
}

// 当前会话所属文件夹 id（无则 null）
function currentFolderId(chatId) {
  return (chatStore.chatMeta[chatId] || {}).folderId || null
}

// 进入"移动到文件夹"目标选择态
function startMoveMenu(chatId) {
  moveMenuId.value = chatId
}

// 从目标选择态返回常规菜单
function backFromMoveMenu(chatId) {
  moveMenuId.value = null
  if (openMenuId.value !== chatId) openMenuId.value = chatId
}

// 执行移动（folderId 为 null 表示移出文件夹），完成后收起菜单
function moveToFolder(chatId, folderId) {
  chatStore.moveChatToFolder(chatId, folderId)
  openMenuId.value = null
  moveMenuId.value = null
}

// 在移动菜单内新建文件夹并立即把该会话移入
async function newFolderAndMove(chatId) {
  const folderId = await promptCreateFolder()
  if (!folderId) return
  chatStore.moveChatToFolder(chatId, folderId)
  openMenuId.value = null
  moveMenuId.value = null
}

// 弹窗输入名称创建文件夹，返回新文件夹 id（取消/重名复用返回对应结果）
async function promptCreateFolder(defaultName) {
  try {
    const { value } = await ElMessageBox.prompt(t('sidebar.newFolderPrompt'), t('sidebar.newFolder'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      inputValue: defaultName || '',
      inputValidator: (v) => {
        const name = (v || '').trim()
        if (!name) return t('sidebar.folderNameRequired')
        if (name.length > 20) return t('sidebar.folderNameMaxLen')
        return true
      }
    })
    return chatStore.createFolder(value)
  } catch {
    return null
  }
}

// 文件夹分组标题右侧的新建入口
async function onCreateFolder() {
  await promptCreateFolder()
}

// 进入或退出会话多选模式，退出时清空选择
function toggleMultiSelect() {
  multiSelectMode.value = !multiSelectMode.value
  selectedChatIds.value = []
  openMenuId.value = null
}

// 切换单个会话的选中状态
function toggleChatSelection(chatId) {
  selectedChatIds.value = selectedChatIds.value.includes(chatId)
    ? selectedChatIds.value.filter(id => id !== chatId)
    : [...selectedChatIds.value, chatId]
}

// 多选态点击会话只切换勾选，普通态仍切换会话
function handleChatClick(chatId) {
  if (multiSelectMode.value) toggleChatSelection(chatId)
  else emit('switch-chat', chatId)
}

// 批量移动到已有或新建文件夹
async function bulkMove() {
  if (!selectedChatIds.value.length) return
  const folderId = await promptCreateFolder()
  if (!folderId) return
  chatStore.moveChatsToFolder(selectedChatIds.value, folderId)
  selectedChatIds.value = []
}

// 二次确认后批量删除选中会话
async function bulkDelete() {
  if (!selectedChatIds.value.length) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${selectedChatIds.value.length} 个会话吗？`, '批量删除会话', {
      confirmButtonText: t('common.delete'), cancelButtonText: t('common.cancel'), type: 'warning'
    })
    chatStore.deleteChats(selectedChatIds.value)
    selectedChatIds.value = []
  } catch { /* 取消 */ }
}

// 文件夹标题行菜单开关
function toggleFolderMenu(folderId) {
  openMenuId.value = null
  moveMenuId.value = null
  openFolderMenuId.value = openFolderMenuId.value === folderId ? null : folderId
}

// 重命名文件夹
async function onRenameFolder(folder) {
  openFolderMenuId.value = null
  try {
    const { value } = await ElMessageBox.prompt(t('sidebar.renameFolderPrompt'), t('sidebar.rename'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      inputValue: folder.name,
      inputValidator: (v) => {
        const name = (v || '').trim()
        if (!name) return t('sidebar.folderNameRequired')
        if (name.length > 20) return t('sidebar.folderNameMaxLen')
        return true
      }
    })
    chatStore.renameFolder(folder.id, value)
  } catch { /* 取消 */ }
}

// 删除文件夹（仅解除分组，会话保留）
async function onDeleteFolder(folder) {
  openFolderMenuId.value = null
  try {
    await ElMessageBox.confirm(
      t('sidebar.deleteFolderConfirm', { name: folder.name }),
      t('sidebar.deleteFolder'),
      {
        confirmButtonText: t('common.delete'),
        cancelButtonText: t('common.cancel'),
        type: 'warning'
      }
    )
    chatStore.deleteFolder(folder.id)
  } catch { /* 取消 */ }
}

async function onRename(chat) {
  openMenuId.value = null
  try {
    const { value } = await ElMessageBox.prompt(t('sidebar.renamePrompt'), t('sidebar.renameTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      inputValue: chat.title || '',
      inputValidator: (v) => (v || '').length <= 40 || t('sidebar.nameMaxLen')
    })
    chatStore.renameChat(chat.id, value)
  } catch { /* 取消 */ }
}

function onShare(id) {
  openMenuId.value = null
  emit('share-chat', id)
}

function onExport(id) {
  // 未加载会话会先拉取正文再导出，失败提示由请求拦截器统一处理
  chatStore.exportChatMarkdown(id).catch(() => {})
  openMenuId.value = null
}

function onDelete(id) {
  openMenuId.value = null
  emit('delete-chat', id)
}

// 跨会话全文搜索：关键字变化后 300ms 防抖调用服务端检索
const searchResults = ref([])
const searching = ref(false)
let searchTimer = null
watch(() => chatStore.searchKeyword, (kw) => {
  if (searchTimer) clearTimeout(searchTimer)
  const q = (kw || '').trim()
  if (!q) {
    searchResults.value = []
    searching.value = false
    return
  }
  searching.value = true
  searchTimer = setTimeout(async () => {
    try {
      const res = await searchChatHistory(q)
      // 只保留当前关键字的结果（避免慢请求覆盖新输入）
      if (q === chatStore.searchKeyword.trim()) {
        searchResults.value = res?.success ? (res.data || []) : []
      }
    } catch {
      searchResults.value = []
    } finally {
      searching.value = false
    }
  }, 300)
})

const userAvatarSrc = computed(() => {
  if (userProfile.value.avatarType === 'svg' && userProfile.value.avatarValue) {
    return 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(userProfile.value.avatarValue)
  }
  return getTheme() === 'dark' ? '/icons/user_ss.svg' : '/icons/user.svg'
})

// 加载侧边栏用户显示名与头像
async function loadUserProfile() {
  try {
    const res = await getUserProfile()
    if (res?.success) userProfile.value = { ...userProfile.value, ...(res.data || {}) }
  } catch (e) { /* 侧边栏回退用户名与默认头像 */ }
}

// 接收个人设置保存后的即时资料更新
function onUserProfileUpdated(event) {
  userProfile.value = { ...userProfile.value, ...(event.detail || {}) }
}

function toggleUserMenu() {
  userMenuOpen.value = !userMenuOpen.value
  // 菜单展开即预热后台入口 chunk，缩短点击"管理后台"后的加载等待
  if (userMenuOpen.value) prefetchAdminEntry()
}

// 预加载后台管理入口 chunk（布局 + 默认首屏"快速接入"页）：
// 路由懒加载的 chunk 若等点击后才下载/解析会阻塞主线程造成卡顿，
// 在打开菜单/悬停入口的间隙提前拉取，点击时即可秒开（import 结果有缓存，flag 防重复触发）
let adminPrefetched = false
function prefetchAdminEntry() {
  if (adminPrefetched || authStore.role !== 'admin') return
  adminPrefetched = true
  import('@/layout/AdminLayout.vue')
  import('@/views/admin/QuickStart.vue')
}

function goAdmin() {
  userMenuOpen.value = false
  router.push('/admin')
}

function closeMenuOnOutside(e) {
  if (userMenuOpen.value) {
    userMenuOpen.value = false
  }
  if (openMenuId.value !== null) {
    openMenuId.value = null
    moveMenuId.value = null
  }
  if (openFolderMenuId.value !== null) {
    openFolderMenuId.value = null
  }
}

onMounted(() => {
  document.addEventListener('click', closeMenuOnOutside)
  window.addEventListener('user-profile-updated', onUserProfileUpdated)
  loadUserProfile()
})

onUnmounted(() => {
  document.removeEventListener('click', closeMenuOnOutside)
  window.removeEventListener('user-profile-updated', onUserProfileUpdated)
  if (scrollHideTimer) clearTimeout(scrollHideTimer)
})

const emit = defineEmits(['toggle', 'new-chat', 'switch-chat', 'delete-chat', 'open-settings', 'open-about', 'open-stats', 'logout', 'share-chat'])
</script>

<style scoped>
/* 会话项：置顶标记与操作菜单 */
.pin-marker {
  flex-shrink: 0;
  color: var(--accent, #4a7dff);
  margin-right: 2px;
}
.chat-item {
  position: relative;
}
.chat-item.selected { background: color-mix(in srgb, var(--primary,#6366f1) 12%, transparent); }
.chat-select-checkbox { width:15px; height:15px; flex:0 0 auto; accent-color:var(--primary,#6366f1); }
.sidebar-header-actions { display:flex; align-items:center; gap:4px; }
.sidebar-header-action {
  display:inline-flex;
  width:24px;
  height:24px;
  align-items:center;
  justify-content:center;
  padding:0;
  border:0;
  border-radius:6px;
  background:transparent;
  color:var(--ink-3,#999);
  cursor:pointer;
  transition:background .15s,color .15s;
}
.sidebar-header-action:hover,
.sidebar-header-action.active { background:var(--primary-soft,#eef3ff); color:var(--primary,#4a7dff); }
.multi-select-toolbar { display:flex; align-items:center; gap:6px; padding:8px 10px; margin-bottom:8px; border:1px solid var(--border,#333); border-radius:6px; color:var(--ink-2,#ccc); font-size:11px; }
.multi-select-toolbar span { margin-right:auto; }
.multi-select-toolbar button { border:0; background:transparent; color:var(--primary,#6366f1); cursor:pointer; font-size:11px; }
.multi-select-toolbar button.danger { color:#ef4444; }
.multi-select-toolbar button:disabled { opacity:.4; cursor:not-allowed; }
.chat-more-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 2px 4px;
  color: var(--ink-3, #999);
  opacity: 0;
  transition: opacity .15s;
  display: inline-flex;
  align-items: center;
}
.chat-item:hover .chat-more-btn,
.chat-item.active .chat-more-btn {
  opacity: 1;
}
.chat-more-btn:hover {
  color: var(--ink-1, #333);
}
.chat-item-menu {
  position: absolute;
  right: 8px;
  top: 100%;
  z-index: 30;
  min-width: 120px;
  background: var(--surface-1, #fff);
  border: 1px solid var(--line-1, #e5e5e5);
  border-radius: 8px;
  box-shadow: 0 6px 20px rgba(0,0,0,.12);
  padding: 4px;
  margin-top: 2px;
}
.chat-item-menu-item {
  padding: 7px 10px;
  font-size: 13px;
  color: var(--ink-1, #333);
  border-radius: 6px;
  cursor: pointer;
  white-space: nowrap;
}
.chat-item-menu-item:hover {
  background: var(--surface-2, #f5f5f5);
}
.chat-item-menu-item-danger {
  color: #e5484d;
}

/* 新建会话按钮行 */
.new-chat-row {
  display: flex;
  align-items: stretch;
  margin-bottom: 16px;
}
.new-chat-row .new-chat-btn {
  flex: 1;
  width: auto;
  min-width: 0;
  margin-bottom: 0;
}

/* 文件夹一级分组标题与日期标题保持相同层级，右侧只保留小型新建入口 */
.folder-section-title { display:flex; align-items:center; justify-content:space-between; }
.folder-title-action {
  display:inline-flex;
  width:22px;
  height:22px;
  align-items:center;
  justify-content:center;
  padding:0;
  border:0;
  border-radius:5px;
  background:transparent;
  color:var(--primary,#4a7dff);
  cursor:pointer;
  transition:background .15s;
}
.folder-title-action:hover { background:var(--primary-soft,#eef3ff); }

/* 文件夹分组标题行 */
.chat-folder-header {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 9px 10px;
  margin-top: 4px;
  border-radius: var(--radius, 10px);
  cursor: pointer;
  color: var(--ink-2, #666);
  transition: background .15s, color .15s;
  user-select: none;
}
.chat-folder-header:hover {
  background: var(--primary-soft, #eef3ff);
  color: var(--fg, #333);
}
.folder-caret {
  flex-shrink: 0;
  transition: transform .15s;
}
.folder-caret.collapsed {
  transform: rotate(-90deg);
}
.folder-icon {
  flex-shrink: 0;
  color: var(--primary, #4a7dff);
}
.folder-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  font-weight: 500;
}
.folder-count {
  flex-shrink: 0;
  font-size: 10px;
  font-family: var(--mono, monospace);
  color: var(--ink-3, #999);
  background: var(--surface-2, #f5f5f5);
  border-radius: 8px;
  padding: 1px 6px;
  min-width: 16px;
  text-align: center;
}
.chat-folder-header .folder-more-btn {
  opacity: 0;
}
.chat-folder-header:hover .folder-more-btn {
  opacity: 1;
}
/* 文件夹内的会话项轻微缩进，体现归属层级 */
.chat-item.in-folder {
  padding-left: 26px;
}

/* 文件夹标题行菜单：复用 chat-item-menu 样式，定位到标题行下方 */
.chat-item-menu.folder-menu {
  right: 4px;
  min-width: 110px;
}

/* 移动到文件夹目标选择态 */
.chat-item-menu-title {
  padding: 6px 10px 4px;
  font-size: 11px;
  color: var(--ink-3, #999);
  user-select: none;
}
.chat-item-menu-empty {
  padding: 6px 10px;
  font-size: 12px;
  color: var(--ink-3, #999);
  user-select: none;
}
.chat-item-menu-divider {
  height: 1px;
  background: var(--line-1, #eee);
  margin: 4px 6px;
}
.chat-item-menu-item-muted {
  color: var(--ink-3, #999);
}
.chat-item-menu-item .move-check {
  display: inline-block;
  width: 14px;
  color: var(--primary, #4a7dff);
  font-size: 12px;
}
.chat-item-menu-item .move-name {
  vertical-align: middle;
}
/* 目标选择态下列表可能较长，限制高度允许滚动 */
.chat-item-menu.is-move-menu {
  max-height: 260px;
  overflow-y: auto;
}

/* 全文搜索命中项：标题 + 匹配片段两行展示 */
.search-hit {
  height: auto;
  padding-top: 6px;
  padding-bottom: 6px;
}
.search-hit-body {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.search-hit-snippet {
  font-size: 11px;
  color: var(--ink-3, #999);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
