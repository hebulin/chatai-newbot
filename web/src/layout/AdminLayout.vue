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
      </el-tabs>
    </div>

    <!-- 主内容区 -->
    <main class="admin-main">
      <router-view />
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

onMounted(() => {
  initTheme()
  // 鉴权检查
  authStore.checkAuth()
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
  backdrop-filter: blur(12px);
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
