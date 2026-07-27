<template>
  <div class="login-page" :class="{ 'form-view': showForm }">
    <div class="paper-grain" aria-hidden="true"></div>
    <div class="ambient" aria-hidden="true"></div>

    <!-- 品牌介绍页 -->
    <section class="view view--brand" aria-label="品牌介绍">
      <div class="view-inner brand-inner">
        <article class="panel brand-panel">
          <span class="brand-watermark" aria-hidden="true">A</span>
          <header class="panel-top brand-top">
            <div class="brand-mark">
              <div class="mark-glyph">
                <span class="mark-line mark-line-1"></span>
                <span class="mark-line mark-line-2"></span>
                <span class="mark-line mark-line-3"></span>
              </div>
              <div class="mark-meta">
                <span class="mark-no">№ 001</span>
                <span class="mark-sep">/</span>
                <span class="mark-vol">VOL. II</span>
              </div>
            </div>
            <button class="theme-toggle" type="button" @click="toggleTheme" aria-label="切换主题">
              <span class="toggle-track"><span class="toggle-knob"></span></span>
            </button>
          </header>

          <div class="brand-body">
            <span class="eyebrow">A WORKBENCH FOR</span>
            <h1 class="display">
              <span class="display-line">Conversations</span>
              <span class="display-line display-italic">that <em>think</em></span>
              <span class="display-line">alongside you.</span>
            </h1>
            <p class="brand-lede">
              Atelier 是一座安静的工坊。它把多家大语言模型收拢在同一张工作台上，让你用最少的仪式感，写下最值得说出口的话。
            </p>
            <div class="brand-foot">
              <div class="foot-stat">
                <span class="foot-stat-no">12</span>
                <span class="foot-stat-lbl">MODELS<br>CURATED</span>
              </div>
              <div class="foot-stat">
                <span class="foot-stat-no">∞</span>
                <span class="foot-stat-lbl">CONTEXT<br>WINDOW</span>
              </div>
              <div class="foot-stat">
                <span class="foot-stat-no">0</span>
                <span class="foot-stat-lbl">AD<br>NOISE</span>
              </div>
            </div>
          </div>

          <div class="brand-cta-row">
            <button class="brand-cta" type="button" @click="enterFormView">
              <span class="cta-text">立刻体验 · TRY NOW</span>
              <span class="cta-arrow">
                <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
              </span>
            </button>
          </div>
        </article>
      </div>
    </section>

    <!-- 登录表单页 -->
    <main class="view view--form" aria-label="登录">
      <div class="view-inner form-inner">
        <article class="panel form-panel">
          <header class="panel-top form-top">
            <button class="back-to-brand" type="button" @click="backToBrand" aria-label="返回介绍页">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
              <span class="back-label">返回</span>
            </button>
            <div class="top-date">{{ currentDate }}</div>
            <button class="theme-toggle" type="button" @click="toggleTheme" aria-label="切换主题">
              <span class="toggle-track"><span class="toggle-knob"></span></span>
            </button>
          </header>

          <section class="form-stage" :class="{ reveal: showForm }">
            <div class="stage-head">
              <span class="stage-no">{{ isRegister ? 'B.' : 'A.' }}</span>
              <span class="stage-eyebrow">{{ isRegister ? 'SIGN UP · 注册' : 'SIGN IN · 登录' }}</span>
            </div>
            <h2 class="stage-title">
              <span class="stage-title-1">{{ isRegister ? '欢迎加入' : '欢迎回来' }}</span>
              <span class="stage-title-2 stage-italic">{{ isRegister ? 'Join the workshop' : 'Welcome back' }}</span>
            </h2>

            <div class="forms-stack">
              <!-- 登录表单 -->
              <form class="atelier-form" :class="{ 'form-pane-active': !isRegister }" @submit.prevent="handleLogin" autocomplete="off">
                <div class="field">
                  <label class="field-label"><span class="field-num">01</span><span class="field-name">用户名 · USERNAME</span></label>
                  <div class="field-input-wrap">
                    <input type="text" v-model="loginForm.username" placeholder="your.name" autocomplete="username" class="field-input" @blur="validateField('loginUsername')" @input="errors.loginUsername = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.loginUsername">{{ errors.loginUsername }}</span>
                </div>
                <div class="field">
                  <label class="field-label"><span class="field-num">02</span><span class="field-name">密码 · PASSWORD</span></label>
                  <div class="field-input-wrap">
                    <input :type="showPassword ? 'text' : 'password'" v-model="loginForm.password" placeholder="••••••••" autocomplete="current-password" class="field-input" @blur="validateField('loginPassword')" @input="errors.loginPassword = ''">
                    <button type="button" class="field-eye" :class="{ on: showPassword }" @click="showPassword = !showPassword" tabindex="-1">
                      <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg>
                    </button>
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.loginPassword">{{ errors.loginPassword }}</span>
                </div>
                <div class="form-row">
                  <label class="check-rail" :class="{ on: rememberMe }" @click.prevent="rememberMe = !rememberMe" @keydown="onCheckKeydown" role="checkbox" tabindex="0" aria-checked="rememberMe">
                    <span class="check-box"><span class="check-tick"></span></span>
                    <span class="check-label">记住我 · REMEMBER</span>
                  </label>
                  <a href="javascript:;" class="link-quiet" @click="isRegister = true">创建账号 -></a>
                </div>
                <button class="submit-cta" type="submit" :disabled="loginLoading">
                  <span class="cta-text" v-if="!loginLoading">进入工坊 · ENTER</span>
                  <span class="cta-text" v-else>登录中...</span>
                  <span class="cta-arrow" v-if="!loginLoading">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
                  </span>
                </button>
              </form>

              <!-- 注册表单 -->
              <form class="atelier-form" :class="{ 'form-pane-active': isRegister }" @submit.prevent="handleRegister" autocomplete="off">
                <div class="field">
                  <label class="field-label"><span class="field-num">01</span><span class="field-name">用户名 · USERNAME</span></label>
                  <div class="field-input-wrap">
                    <input type="text" v-model="regForm.username" placeholder="2-20个字符" autocomplete="username" class="field-input" @blur="validateField('regUsername')" @input="errors.regUsername = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regUsername">{{ errors.regUsername }}</span>
                </div>
                <div class="field">
                  <label class="field-label"><span class="field-num">02</span><span class="field-name">密码 · PASSWORD</span></label>
                  <div class="field-input-wrap">
                    <input type="password" v-model="regForm.password" placeholder="至少4个字符" autocomplete="new-password" class="field-input" @blur="validateField('regPassword')" @input="errors.regPassword = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regPassword">{{ errors.regPassword }}</span>
                </div>
                <div class="field">
                  <label class="field-label"><span class="field-num">03</span><span class="field-name">确认 · CONFIRM</span></label>
                  <div class="field-input-wrap">
                    <input type="password" v-model="regForm.password2" placeholder="再次输入" autocomplete="new-password" class="field-input" @blur="validateField('regPassword2')" @input="errors.regPassword2 = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regPassword2">{{ errors.regPassword2 }}</span>
                </div>
                <button class="submit-cta" type="submit" :disabled="regLoading">
                  <span class="cta-text" v-if="!regLoading">创建账号 · CREATE</span>
                  <span class="cta-text" v-else>注册中...</span>
                  <span class="cta-arrow" v-if="!regLoading">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
                  </span>
                </button>
                <div class="form-row form-row-end">
                  <a href="javascript:;" class="link-quiet" @click="isRegister = false">← 返回登录</a>
                </div>
              </form>
            </div>

            <div class="form-foot">
              <div class="foot-line"></div>
              <div class="foot-meta">
                <span class="meta-l">SECURE · TOKEN BASED</span>
                <span class="meta-c">v{{ APP_VERSION }}</span>
                <span class="meta-r">© ATELIER</span>
              </div>
            </div>
          </section>
        </article>
      </div>
    </main>
  </div>
</template>

<script setup>
import '@/styles/login.css'
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register, getMe } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { APP_VERSION } from '@/config/version'

const router = useRouter()
const authStore = useAuthStore()
const { toggleTheme } = useTheme()

const showForm = ref(false)
const isRegister = ref(false)
const showPassword = ref(false)
const rememberMe = ref(false)
const loginLoading = ref(false)
const regLoading = ref(false)

const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', password2: '' })

// 字段级实时校验错误信息
const errors = ref({ loginUsername: '', loginPassword: '', regUsername: '', regPassword: '', regPassword2: '' })

// 安全访问 localStorage（隐私模式/禁用存储时不抛错）
function safeGet(k) { try { return localStorage.getItem(k) } catch (e) { return null } }
function safeSet(k, v) { try { localStorage.setItem(k, v) } catch (e) { /* ignore */ } }
function safeRemove(k) { try { localStorage.removeItem(k) } catch (e) { /* ignore */ } }

const currentDate = computed(() => {
  const d = new Date()
  const days = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT']
  const pad = n => n < 10 ? '0' + n : '' + n
  return days[d.getDay()] + ' · ' + d.getFullYear() + '.' + pad(d.getMonth() + 1) + '.' + pad(d.getDate())
})

// 已登录自动跳转
onMounted(async () => {
  const token = safeGet('token')
  if (token) {
    try {
      const data = await getMe()
      if (data && data.success) {
        router.replace('/')
        return
      }
    } catch (e) { /* ignore */ }
  }
  // 记住我：恢复用户名
  const flag = safeGet('rememberMe')
  if (flag === '1') {
    rememberMe.value = true
    loginForm.value.username = safeGet('rememberedUsername') || ''
  }
  // 移动端键盘适配：监听 visualViewport 写入 --vvh/--vv-top（login.css 已消费该变量）
  syncVisualViewport()
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', onViewportChange)
    window.visualViewport.addEventListener('scroll', onViewportChange)
  }
  window.addEventListener('resize', scheduleViewportResync)
  document.addEventListener('focusin', onFocusIn)
  document.addEventListener('focusout', scheduleViewportResync)
})

onUnmounted(() => {
  if (window.visualViewport) {
    window.visualViewport.removeEventListener('resize', onViewportChange)
    window.visualViewport.removeEventListener('scroll', onViewportChange)
  }
  window.removeEventListener('resize', scheduleViewportResync)
  document.removeEventListener('focusin', onFocusIn)
  document.removeEventListener('focusout', scheduleViewportResync)
  if (viewportTimer) clearTimeout(viewportTimer)
})

// 写入动态视口高度变量，供 login.css 两屏高度与键盘适配使用
function syncVisualViewport() {
  const vv = window.visualViewport
  if (!vv) return
  document.documentElement.style.setProperty('--vvh', vv.height + 'px')
  document.documentElement.style.setProperty('--vv-top', vv.offsetTop + 'px')
}
function onViewportChange() { syncVisualViewport() }
let viewportTimer = null
// 失焦后延迟复查视口，避免键盘收起后视口收缩态残留白屏
function scheduleViewportResync() {
  if (viewportTimer) clearTimeout(viewportTimer)
  viewportTimer = setTimeout(syncVisualViewport, 120)
}
// 输入框聚焦时平滑滚动到可视区中央，避免被弹起键盘遮挡
function onFocusIn(e) {
  const el = e.target
  if (!el || !el.classList || !el.classList.contains('field-input')) return
  setTimeout(() => {
    const vv = window.visualViewport
    if (!vv) return
    const rect = el.getBoundingClientRect()
    const vvTop = vv.offsetTop
    const vvBottom = vvTop + vv.height
    if (rect.top < vvTop || rect.bottom > vvBottom) {
      const offset = rect.top + window.scrollY - vvTop - (vv.height - rect.height) / 2
      window.scrollTo({ top: Math.max(0, offset), behavior: 'smooth' })
    }
  }, 300)
}

// 进入表单视图：重放级联动画 + 滚回顶部 + 聚焦首个输入框（与旧版收尾逻辑一致）
async function enterFormView() {
  showForm.value = true
  try { history.pushState({ atelierView: 'form' }, '') } catch (e) { /* ignore */ }
  await nextTick()
  const stage = document.querySelector('.form-stage')
  if (stage) {
    stage.classList.remove('reveal')
    void stage.offsetWidth  // 强制回流，确保动画重播
    stage.classList.add('reveal')
  }
  window.scrollTo(0, 0)
  setTimeout(() => {
    if (window.innerWidth > 768) {
      const firstInput = document.querySelector('.form-pane-active .field-input')
      if (firstInput) firstInput.focus()
    }
  }, 520)
}

// 返回介绍页：先收起软键盘再切换视图
function backToBrand() {
  if (document.activeElement && typeof document.activeElement.blur === 'function') {
    document.activeElement.blur()
  }
  showForm.value = false
  if (history.state && history.state.atelierView === 'form') {
    try { history.back() } catch (e) { /* ignore */ }
  }
}

// 复选框键盘可访问性：空格键切换勾选状态
function onCheckKeydown(e) {
  if (e.key === ' ' || e.key === 'Spacebar' || e.code === 'Space') {
    e.preventDefault()
    rememberMe.value = !rememberMe.value
  }
}

// 浏览器前进/后退
if (typeof window !== 'undefined') {
  window.addEventListener('popstate', () => {
    if (history.state && history.state.atelierView === 'form') {
      showForm.value = true
    } else {
      showForm.value = false
    }
  })
}

// 字段级实时校验（失焦触发）
function validateField(field) {
  const e = errors.value
  if (field === 'loginUsername') {
    e.loginUsername = loginForm.value.username.trim() ? '' : '请输入用户名'
  } else if (field === 'loginPassword') {
    e.loginPassword = loginForm.value.password ? '' : '请输入密码'
  } else if (field === 'regUsername') {
    const v = regForm.value.username.trim()
    if (!v) e.regUsername = '请输入用户名'
    else if (v.length < 2 || v.length > 20) e.regUsername = '用户名需 2-20 个字符'
    else e.regUsername = ''
  } else if (field === 'regPassword') {
    const v = regForm.value.password
    if (!v) e.regPassword = '请输入密码'
    else if (v.length < 4) e.regPassword = '密码至少 4 个字符'
    else e.regPassword = ''
  } else if (field === 'regPassword2') {
    const v = regForm.value.password2
    if (!v) e.regPassword2 = '请再次输入密码'
    else if (v !== regForm.value.password) e.regPassword2 = '两次输入的密码不一致'
    else e.regPassword2 = ''
  }
}

// 登录表单整体校验，返回是否通过
function validateLogin() {
  validateField('loginUsername')
  validateField('loginPassword')
  return !errors.value.loginUsername && !errors.value.loginPassword
}

// 注册表单整体校验，返回是否通过
function validateRegister() {
  validateField('regUsername')
  validateField('regPassword')
  validateField('regPassword2')
  return !errors.value.regUsername && !errors.value.regPassword && !errors.value.regPassword2
}

async function handleLogin() {
  if (!validateLogin()) return
  loginLoading.value = true
  try {
    const data = await login({ username: loginForm.value.username, password: loginForm.value.password })
    if (data.success) {
      // 记住我
      if (rememberMe.value) {
        safeSet('rememberMe', '1')
        safeSet('rememberedUsername', data.username || loginForm.value.username)
      } else {
        safeRemove('rememberMe')
        safeRemove('rememberedUsername')
      }
      authStore.setAuth(data)
      router.push('/')
    } else {
      ElMessage.error(data.message || '登录失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请稍后重试')
  } finally {
    loginLoading.value = false
  }
}

async function handleRegister() {
  if (!validateRegister()) return
  regLoading.value = true
  try {
    const data = await register({ username: regForm.value.username, password: regForm.value.password })
    if (data.success) {
      authStore.setAuth(data)
      router.push('/')
    } else {
      ElMessage.error(data.message || '注册失败')
    }
  } catch (e) {
    ElMessage.error('网络错误，请稍后重试')
  } finally {
    regLoading.value = false
  }
}
</script>

<style scoped>
/* 字段级实时校验错误提示 */
.field-error {
  display: block;
  margin-top: 6px;
  font-size: 11px;
  color: #ef4444;
  letter-spacing: 0.02em;
}
/* 复选框键盘聚焦可见 */
.check-rail:focus-visible {
  outline: 2px solid var(--primary, #6366f1);
  outline-offset: 3px;
  border-radius: 4px;
}
</style>
