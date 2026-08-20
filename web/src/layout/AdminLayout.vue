<template>
  <div class="admin-layout">
    <!-- 顶部导航 -->
    <nav class="admin-nav">
      <div class="nav-brand">
        <span class="nav-brand-icon">
          <el-icon :size="18"><Grid /></el-icon>
        </span>
        <span class="nav-brand-text">
          <span class="nav-brand-name">Atelier</span>
          <span class="nav-brand-sub">管理后台 · ADMIN</span>
        </span>
      </div>
      <div class="nav-actions">
        <button class="theme-toggle-btn" @click="handleToggleTheme" title="切换主题" aria-label="主题">
          <span class="toggle-track"><span class="toggle-knob"></span></span>
        </button>
        <el-button text @click="goChat">
          <el-icon><Back /></el-icon>
          <span>返回聊天</span>
        </el-button>
      </div>
    </nav>

    <!-- Tab 导航 -->
    <div class="admin-tabs-wrapper">
      <el-tabs v-model="activeTab" @tab-change="onTabChange" class="admin-tabs">
        <el-tab-pane label="快速接入" name="quick-start" />
        <el-tab-pane label="模型管理" name="models" />
        <el-tab-pane label="厂商管理" name="providers" />
        <el-tab-pane label="用户管理" name="users" />
        <el-tab-pane label="分享管理" name="shares" />
        <el-tab-pane label="系统设置" name="settings" />
        <el-tab-pane label="联网配置" name="websearch" />
        <el-tab-pane label="公告管理" name="announcements" />
        <el-tab-pane label="审计日志" name="audit-logs" />
      </el-tabs>
    </div>

    <!-- 主内容区：keep-alive 缓存已访问的 tab 页面（切回秒开、保留筛选/分页状态），
         transition 以淡出淡入过渡缓解内容突变的生硬感 -->
    <main class="admin-main">
      <router-view v-slot="{ Component }">
        <transition name="admin-page" mode="out-in">
          <keep-alive>
            <component :is="Component" :key="route.path" />
          </keep-alive>
        </transition>
      </router-view>
    </main>

    <!-- 底部 -->
    <footer class="admin-footer">
      <span class="app-version">v{{ APP_VERSION }}</span>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Grid, Back } from '@element-plus/icons-vue'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { APP_VERSION } from '@/config/version'

const router = useRouter()
const route = useRoute()
const authStore = useAuthStore()
const { toggleTheme, initTheme, getTheme } = useTheme()

const activeTab = ref('quick-start')

// 同步路由与 Tab
watch(() => route.path, (path) => {
  const tab = path.replace(/^\/admin\//, '') || 'quick-start'
  activeTab.value = tab
}, { immediate: true })

function onTabChange(name) {
  router.push('/admin/' + name)
}

function handleToggleTheme() {
  toggleTheme()
}

function goChat() {
  router.push('/')
}

// 空闲时依次预加载全部后台子页面的异步 chunk：
// 避免切换 tab 时现场下载/解析 chunk 阻塞主线程，导致"tab 已切换但内容不变、随后突然跳变"的卡顿
function prefetchAdminViews() {
  const loaders = [
    () => import('@/views/admin/QuickStart.vue'),
    () => import('@/views/admin/Models.vue'),
    () => import('@/views/admin/Providers.vue'),
    () => import('@/views/admin/Users.vue'),
    () => import('@/views/admin/Shares.vue'),
    () => import('@/views/admin/Settings.vue'),
    () => import('@/views/admin/WebSearch.vue'),
    () => import('@/views/admin/Announcements.vue'),
    () => import('@/views/admin/AuditLogs.vue')
  ]
  // 优先用浏览器空闲回调逐个加载（不抢占首屏渲染）；不支持时退化为错峰 setTimeout
  const schedule = window.requestIdleCallback
    ? (cb) => window.requestIdleCallback(cb, { timeout: 2000 })
    : (cb) => setTimeout(cb, 300)
  let idx = 0
  const loadNext = () => {
    if (idx >= loaders.length) return
    loaders[idx++]()
    schedule(loadNext)
  }
  schedule(loadNext)
}

onMounted(() => {
  initTheme()
  // 鉴权检查
  authStore.checkAuth()
  // 进入后台后利用空闲时段预热其余 tab 页面 chunk
  prefetchAdminViews()
})
</script>

<style scoped>
/* chat.css 全局锁死了 html/body 的滚动（overflow:hidden，聊天页自行管理滚动），
   该 CSS 经 JS 引入后常驻文档，会波及后台管理页，导致 body 无法滚动。
   故这里让 .admin-layout 自己作为滚动容器，数据量多时可正常下滑 */
.admin-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  overflow-y: auto;
  overflow-x: hidden;
}
@supports (height: 100dvh) {
  .admin-layout { height: 100dvh; }
}
.admin-nav {
  position: sticky;
  top: 0;
  z-index: 50;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  height: 60px;
  background: var(--bg-2);
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
  /* 注：不使用 backdrop-filter 模糊——背景色 var(--bg-2) 本就不透明，模糊无视觉效果，
     且粘性定位 + backdrop-filter 在滚动时需持续重绘下方内容，是滚动掉帧的常见元凶 */
}
.nav-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.nav-brand-icon {
  width: 34px;
  height: 34px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--primary);
  background: var(--paper-2);
  border: 1px solid var(--border);
  border-radius: 8px;
}
.nav-brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.nav-brand-name {
  font-weight: 600;
  font-size: 16px;
  color: var(--ink);
}
.nav-brand-sub {
  font-family: var(--mono);
  font-size: 10px;
  color: var(--ink-3);
  letter-spacing: 0.06em;
  margin-top: 2px;
}
.nav-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
/* Theme toggle - pill-shaped switch (unified with chat/login) */
.theme-toggle-btn {
  width: 48px; height: 26px;
  background: var(--paper-2);
  border: 1px solid var(--border);
  border-radius: 13px;
  padding: 2px;
  cursor: pointer;
  position: relative;
  transition: all 0.3s var(--ease);
  display: flex;
  align-items: center;
}
.theme-toggle-btn:hover { border-color: var(--primary); }
.toggle-track { display: block; position: relative; width: 100%; height: 100%; }
.toggle-knob {
  position: absolute;
  top: 0; left: 0;
  width: 20px; height: 20px;
  border-radius: 50%;
  background: var(--primary);
  transition: all 0.4s var(--ease);
  box-shadow: 0 1px 4px rgba(0,0,0,0.2);
}
.toggle-knob::before {
  content: '\263E';
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: #fff;
  line-height: 1;
}
[data-theme="light"] .toggle-knob { left: calc(100% - 20px); background: var(--primary); }
[data-theme="light"] .toggle-knob::before { content: '\2600'; color: #fff; }
.admin-tabs-wrapper {
  padding: 0 32px;
  background: var(--bg-2);
  border-bottom: 1px solid var(--border);
}
.admin-tabs :deep(.el-tabs__header) {
  margin: 0;
  border-bottom: none;
}
.admin-tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.admin-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 500;
  color: var(--ink-2);
  height: 44px;
  line-height: 44px;
}
.admin-tabs :deep(.el-tabs__item.is-active) {
  color: var(--primary);
}
.admin-tabs :deep(.el-tabs__active-bar) {
  background-color: var(--primary);
}
.admin-main {
  flex: 1;
  padding: 24px 32px;
  max-width: 1400px;
  width: 100%;
  margin: 0 auto;
}
.admin-footer {
  padding: 16px 32px;
  text-align: center;
  color: var(--ink-4);
  font-size: 12px;
  border-top: 1px solid var(--border);
}

/* 移动端适配 */
@media (max-width: 640px) {
  .admin-nav {
    padding: 0 12px;
    height: 52px;
  }
  .nav-brand-icon {
    width: 28px;
    height: 28px;
  }
  .nav-brand-name {
    font-size: 14px;
  }
  .nav-brand-sub {
    font-size: 9px;
  }
  .admin-tabs-wrapper {
    padding: 0 12px;
  }
  .admin-tabs :deep(.el-tabs__item) {
    font-size: 13px;
    height: 40px;
    line-height: 40px;
    padding: 0 12px;
  }
  .admin-main {
    padding: 16px 12px;
  }
  .admin-footer {
    padding: 12px 16px;
  }
}
</style>

<!-- 过渡类作用于子页面根元素（跨组件），需非 scoped 全局样式 -->
<style>
/* 后台 tab 页面切换过渡：旧页快速淡出，新页轻微上浮淡入，
   缓解路由内容"突然一变"的生硬感；时长控制在 180ms 内不拖慢操作节奏 */
.admin-page-enter-active,
.admin-page-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}
.admin-page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}
.admin-page-leave-to {
  opacity: 0;
}
</style>
