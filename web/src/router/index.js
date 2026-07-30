import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/LoginView.vue'),
    meta: { public: true, title: '登录' }
  },
  {
    path: '/',
    name: 'Chat',
    component: () => import('@/views/ChatView.vue'),
    meta: { title: '工作台' }
  },
  {
    path: '/share/:id',
    name: 'Share',
    component: () => import('@/views/ShareView.vue'),
    // allowAuthed：已登录用户也可直接查看分享页，不重定向回首页
    meta: { public: true, allowAuthed: true, title: '分享的会话' }
  },
  {
    path: '/admin',
    component: () => import('@/layout/AdminLayout.vue'),
    redirect: '/admin/quick-start',
    meta: { requiresAdmin: true },
    children: [
      {
        path: 'quick-start',
        name: 'QuickStart',
        component: () => import('@/views/admin/QuickStart.vue'),
        meta: { title: '快速接入' }
      },
      {
        path: 'models',
        name: 'Models',
        component: () => import('@/views/admin/Models.vue'),
        meta: { title: '模型管理' }
      },
      {
        path: 'providers',
        name: 'Providers',
        component: () => import('@/views/admin/Providers.vue'),
        meta: { title: '厂商管理' }
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('@/views/admin/Users.vue'),
        meta: { title: '用户管理' }
      },
      {
        path: 'shares',
        name: 'Shares',
        component: () => import('@/views/admin/Shares.vue'),
        meta: { title: '分享管理' }
      },
      {
        path: 'settings',
        name: 'Settings',
        component: () => import('@/views/admin/Settings.vue'),
        meta: { title: '系统设置' }
      },
      {
        path: 'websearch',
        name: 'WebSearch',
        component: () => import('@/views/admin/WebSearch.vue'),
        meta: { title: '联网功能配置' }
      },
      {
        path: 'announcements',
        name: 'Announcements',
        component: () => import('@/views/admin/Announcements.vue'),
        meta: { title: '系统公告管理' }
      },
      {
        path: 'audit-logs',
        name: 'AuditLogs',
        component: () => import('@/views/admin/AuditLogs.vue'),
        meta: { title: '审计日志' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory('/'),
  routes
})

// 路由守卫：鉴权（token 存于 HttpOnly Cookie 不可读，以 username 作为本地登录态标记，真实校验由后端 401 兜底）
router.beforeEach(async (to, from, next) => {
  const loggedIn = !!localStorage.getItem('username')

  // 公开页面（登录页 / 分享页）
  if (to.meta.public) {
    // 已登录用户访问登录页 → 跳转到首页（分享页 allowAuthed 除外）
    if (loggedIn && !to.meta.allowAuthed) {
      next('/')
      return
    }
    next()
    return
  }

  // 需要登录的页面
  if (!loggedIn) {
    next('/login')
    return
  }

  // 需要管理员权限的页面
  if (to.meta.requiresAdmin || to.matched.some(r => r.meta.requiresAdmin)) {
    const role = localStorage.getItem('role')
    if (role !== 'admin') {
      next('/')
      return
    }
  }

  next()
})

export default router
