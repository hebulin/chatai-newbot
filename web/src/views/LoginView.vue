<template>
  <div class="login-page" :class="{ 'form-view': showForm }" :inert="twoFactorRequired" :aria-hidden="twoFactorRequired ? 'true' : undefined">
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
            <button class="theme-toggle" type="button" @click="toggleTheme" :aria-label="t('login.toggleTheme')">
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
              {{ t('login.lede') }}
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
              <span class="cta-text">{{ t('login.tryNow') }}</span>
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
            <button class="back-to-brand" type="button" @click="backToBrand" :aria-label="t('login.back')">
              <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/></svg>
              <span class="back-label">{{ t('login.back') }}</span>
            </button>
            <div class="top-date">{{ currentDate }}</div>
            <button class="theme-toggle" type="button" @click="toggleTheme" :aria-label="t('login.toggleTheme')">
              <span class="toggle-track"><span class="toggle-knob"></span></span>
            </button>
          </header>

          <section class="form-stage" :class="{ reveal: showForm }">
            <div class="stage-head">
              <span class="stage-no">{{ isRegister ? 'B.' : 'A.' }}</span>
              <span class="stage-eyebrow">{{ isRegister ? t('login.signUp') : t('login.signIn') }}</span>
            </div>
            <h2 class="stage-title">
              <span class="stage-title-1">{{ isRegister ? t('login.welcomeJoin') : t('login.welcomeBack') }}</span>
              <span class="stage-title-2 stage-italic">{{ isRegister ? 'Join the workshop' : 'Welcome back' }}</span>
            </h2>

            <div class="forms-stack">
              <!-- 登录表单 -->
              <form class="atelier-form" :class="{ 'form-pane-active': !isRegister }" @submit.prevent="handleLogin" autocomplete="off">
                <div class="field">
                  <label class="field-label" for="login-username"><span class="field-num">01</span><span class="field-name">{{ t('login.username') }}</span></label>
                  <div class="field-input-wrap">
                    <input id="login-username" type="text" v-model="loginForm.username" placeholder="your.name" autocomplete="username" class="field-input" :aria-invalid="!!errors.loginUsername" aria-describedby="login-username-error" @blur="validateField('loginUsername')" @input="errors.loginUsername = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span id="login-username-error" class="field-error" v-if="errors.loginUsername">{{ errors.loginUsername }}</span>
                </div>
                <div class="field">
                  <label class="field-label" for="login-password"><span class="field-num">02</span><span class="field-name">{{ t('login.password') }}</span></label>
                  <div class="field-input-wrap">
                    <input id="login-password" :type="showPassword ? 'text' : 'password'" v-model="loginForm.password" placeholder="••••••••" autocomplete="current-password" class="field-input" :aria-invalid="!!errors.loginPassword" aria-describedby="login-password-error" @blur="validateField('loginPassword')" @input="errors.loginPassword = ''">
                    <button type="button" class="field-eye" :class="{ on: showPassword }" @click="showPassword = !showPassword" :aria-label="t('login.togglePassword')">
                      <svg aria-hidden="true" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"/><circle cx="12" cy="12" r="3"/></svg>
                    </button>
                    <span class="field-bar"></span>
                  </div>
                  <span id="login-password-error" class="field-error" v-if="errors.loginPassword">{{ errors.loginPassword }}</span>
                </div>
                <div class="form-row">
                  <label class="check-rail" :class="{ on: rememberMe }" @click.prevent="rememberMe = !rememberMe" @keydown="onCheckKeydown" role="checkbox" tabindex="0" :aria-checked="rememberMe">
                    <span class="check-box"><span class="check-tick"></span></span>
                    <span class="check-label">{{ t('login.rememberMe') }}</span>
                  </label>
                  <button v-if="registerConfig.enabled" type="button" class="link-quiet link-button" @click="openRegister">{{ t('login.createAccountLink') }}</button>
                  <span v-else class="link-quiet">注册已关闭</span>
                </div>
                <button ref="loginSubmitButton" class="submit-cta" type="submit" :disabled="loginLoading" aria-haspopup="dialog">
                  <span class="cta-text" v-if="!loginLoading">{{ t('login.enterCta') }}</span>
                  <span class="cta-text" v-else>{{ t('login.loggingIn') }}</span>
                  <span class="cta-arrow" v-if="!loginLoading">
                    <svg aria-hidden="true" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
                  </span>
                </button>
              </form>

              <!-- 注册表单 -->
              <form class="atelier-form" :class="{ 'form-pane-active': isRegister }" @submit.prevent="handleRegister" autocomplete="off">
                <div class="field">
                  <label class="field-label" for="reg-username"><span class="field-num">01</span><span class="field-name">{{ t('login.username') }}</span></label>
                  <div class="field-input-wrap">
                    <input id="reg-username" type="text" v-model="regForm.username" :placeholder="t('login.regUsernamePlaceholder')" autocomplete="username" class="field-input" @blur="validateField('regUsername')" @input="errors.regUsername = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regUsername">{{ errors.regUsername }}</span>
                </div>
                <div class="field">
                  <label class="field-label" for="reg-password"><span class="field-num">02</span><span class="field-name">{{ t('login.password') }}</span></label>
                  <div class="field-input-wrap">
                    <input id="reg-password" type="password" v-model="regForm.password" :placeholder="t('login.regPasswordPlaceholder')" autocomplete="new-password" class="field-input" @blur="validateField('regPassword')" @input="errors.regPassword = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regPassword">{{ errors.regPassword }}</span>
                </div>
                <div class="field">
                  <label class="field-label" for="reg-password-confirm"><span class="field-num">03</span><span class="field-name">{{ t('login.confirm') }}</span></label>
                  <div class="field-input-wrap">
                    <input id="reg-password-confirm" type="password" v-model="regForm.password2" :placeholder="t('login.regPassword2Placeholder')" autocomplete="new-password" class="field-input" @blur="validateField('regPassword2')" @input="errors.regPassword2 = ''">
                    <span class="field-bar"></span>
                  </div>
                  <span class="field-error" v-if="errors.regPassword2">{{ errors.regPassword2 }}</span>
                </div>
                <div v-if="registerConfig.inviteRequired" class="field">
                  <label class="field-label" for="reg-invite"><span class="field-num">04</span><span class="field-name">邀请码</span></label>
                  <div class="field-input-wrap">
                    <input id="reg-invite" type="text" v-model="regForm.inviteCode" autocomplete="off" class="field-input" placeholder="请输入管理员提供的邀请码">
                    <span class="field-bar"></span>
                  </div>
                </div>
                <div v-if="registerConfig.captchaEnabled" class="field">
                  <label class="field-label" for="reg-captcha"><span class="field-num">05</span><span class="field-name">验证码：{{ registerConfig.captcha?.question }}</span></label>
                  <div class="field-input-wrap captcha-input-wrap">
                    <input id="reg-captcha" type="text" inputmode="numeric" v-model="regForm.captchaAnswer" autocomplete="off" class="field-input" placeholder="请输入计算结果">
                    <button type="button" class="link-quiet link-button captcha-refresh" @click="loadRegisterConfig">刷新</button>
                    <span class="field-bar"></span>
                  </div>
                </div>
                <button class="submit-cta" type="submit" :disabled="regLoading">
                  <span class="cta-text" v-if="!regLoading">{{ t('login.createCta') }}</span>
                  <span class="cta-text" v-else>{{ t('login.registering') }}</span>
                  <span class="cta-arrow" v-if="!regLoading">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>
                  </span>
                </button>
                <div class="form-row form-row-end">
                  <a href="javascript:;" class="link-quiet" @click="isRegister = false">{{ t('login.backToLogin') }}</a>
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

    <Teleport to="body">
      <div v-if="twoFactorRequired" class="two-factor-overlay">
        <section ref="twoFactorModal" class="two-factor-modal" role="dialog" aria-modal="true" aria-labelledby="two-factor-title" aria-describedby="two-factor-help" :aria-busy="loginLoading" tabindex="-1" @keydown.esc="closeTwoFactorModal" @keydown.tab="trapTwoFactorFocus">
          <header class="two-factor-modal-head">
            <div>
              <span class="two-factor-kicker">{{ t('login.twoFactorEyebrow') }}</span>
              <h2 id="two-factor-title">{{ t('login.twoFactorTitle') }}</h2>
            </div>
            <button type="button" class="two-factor-close" :disabled="loginLoading" :aria-label="t('common.close')" @click="closeTwoFactorModal">
              <svg aria-hidden="true" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.7"><line x1="6" y1="6" x2="18" y2="18"/><line x1="18" y1="6" x2="6" y2="18"/></svg>
            </button>
          </header>
          <p id="two-factor-help" class="two-factor-help">{{ t('login.twoFactorHelp', { username: twoFactorUsername }) }}</p>
          <form class="two-factor-form" @submit.prevent="handleTwoFactorLogin">
            <div class="two-factor-methods" role="group" :aria-label="t('login.twoFactorMethod')">
              <button type="button" class="method-button" :class="{ active: twoFactorMethod === 'totp' }" :aria-pressed="twoFactorMethod === 'totp'" @click="setTwoFactorMethod('totp')">{{ t('login.authenticatorCode') }}</button>
              <button type="button" class="method-button" :class="{ active: twoFactorMethod === 'recovery' }" :aria-pressed="twoFactorMethod === 'recovery'" @click="setTwoFactorMethod('recovery')">{{ t('login.recoveryCode') }}</button>
            </div>
            <div class="field two-factor-code-field">
              <label class="field-label" for="two-factor-code"><span class="field-num">03</span><span class="field-name">{{ twoFactorMethod === 'totp' ? t('login.authenticatorCode') : t('login.recoveryCode') }}</span></label>
              <div class="field-input-wrap">
                <input id="two-factor-code" ref="twoFactorInput" type="text" v-model="twoFactorCode" :inputmode="twoFactorMethod === 'totp' ? 'numeric' : 'text'" autocomplete="one-time-code" class="field-input code-input" :maxlength="twoFactorMethod === 'totp' ? 6 : 24" :placeholder="twoFactorMethod === 'totp' ? '000000' : 'XXXX-XXXX-XXXX-XXXX'" :aria-invalid="!!twoFactorError" aria-describedby="two-factor-error" @input="normalizeTwoFactorInput">
                <span class="field-bar"></span>
              </div>
              <span id="two-factor-error" class="field-error two-factor-error" aria-live="polite">{{ twoFactorError }}</span>
            </div>
            <div class="two-factor-actions">
              <button type="button" class="two-factor-secondary" :disabled="loginLoading" @click="closeTwoFactorModal">{{ t('common.cancel') }}</button>
              <button type="submit" class="two-factor-primary" :disabled="loginLoading">
                {{ loginLoading ? t('login.verifying') : t('login.verifyCta') }}
              </button>
            </div>
          </form>
        </section>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import '@/styles/login.css'
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { login, register, getMe, verifyTwoFactorLogin, getRegisterConfig } from '@/api/auth'
import { useAuthStore } from '@/stores/auth'
import { useTheme } from '@/composables/useTheme'
import { APP_VERSION } from '@/config/version'

const router = useRouter()
const authStore = useAuthStore()
const { toggleTheme } = useTheme()
const { t } = useI18n()

const showForm = ref(false)
const isRegister = ref(false)
const showPassword = ref(false)
const rememberMe = ref(false)
const loginLoading = ref(false)
const regLoading = ref(false)
const twoFactorRequired = ref(false)
const twoFactorChallenge = ref('')
const twoFactorUsername = ref('')
const twoFactorMethod = ref('totp')
const twoFactorCode = ref('')
const twoFactorError = ref('')
const twoFactorInput = ref(null)
const twoFactorModal = ref(null)
const loginSubmitButton = ref(null)

const loginForm = ref({ username: '', password: '' })
const regForm = ref({ username: '', password: '', password2: '', inviteCode: '', captchaAnswer: '' })
const registerConfig = ref({ enabled: true, inviteRequired: false, captchaEnabled: false, captcha: null })

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

// 已登录自动跳转（token 存于 HttpOnly Cookie，以 username 本地标记预判，再请求后端确认）
onMounted(async () => {
  await loadRegisterConfig()
  if (safeGet('username')) {
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

// 刷新公开注册配置及一次性验证码挑战
async function loadRegisterConfig() {
  try {
    const res = await getRegisterConfig()
    if (res?.success) registerConfig.value = res.data || registerConfig.value
  } catch (e) { /* 登录页仍可正常登录 */ }
}

// 进入注册表单前重新获取挑战，避免使用过期验证码
async function openRegister() {
  await loadRegisterConfig()
  if (!registerConfig.value.enabled) {
    ElMessage.info('系统当前已关闭注册')
    return
  }
  regForm.value.captchaAnswer = ''
  isRegister.value = true
}

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
    e.loginUsername = loginForm.value.username.trim() ? '' : t('login.errUsernameRequired')
  } else if (field === 'loginPassword') {
    e.loginPassword = loginForm.value.password ? '' : t('login.errPasswordRequired')
  } else if (field === 'regUsername') {
    const v = regForm.value.username.trim()
    if (!v) e.regUsername = t('login.errUsernameRequired')
    else if (v.length < 2 || v.length > 20) e.regUsername = t('login.errUsernameLen')
    else e.regUsername = ''
  } else if (field === 'regPassword') {
    const v = regForm.value.password
    if (!v) e.regPassword = t('login.errPasswordRequired')
    else if (v.length < 8 || !/[A-Za-z]/.test(v) || !/\d/.test(v)) e.regPassword = '密码至少 8 位，且需同时包含字母和数字'
    else e.regPassword = ''
  } else if (field === 'regPassword2') {
    const v = regForm.value.password2
    if (!v) e.regPassword2 = t('login.errPassword2Required')
    else if (v !== regForm.value.password) e.regPassword2 = t('login.errPasswordMismatch')
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

// 提交账号密码；启用 2FA 时仅打开验证弹窗，不提前写入登录态
async function handleLogin() {
  if (!validateLogin()) return
  loginLoading.value = true
  try {
    const data = await login({ username: loginForm.value.username, password: loginForm.value.password })
    if (data.success && data.requiresTwoFactor) {
      twoFactorRequired.value = true
      twoFactorChallenge.value = data.challengeToken || ''
      twoFactorUsername.value = data.username || loginForm.value.username
      loginForm.value.password = ''
      loginLoading.value = false
      await nextTick()
      twoFactorInput.value?.focus()
      return
    }
    if (data.success) {
      finishLogin(data)
      return
    }
    ElMessage.error(data.message || t('login.loginFailed'))
  } catch (e) {
    ElMessage.error(t('login.networkError'))
  }
  loginLoading.value = false
}

// 完成二次验证登录，并仅在正式签发会话后保存“记住我”状态
async function handleTwoFactorLogin() {
  const normalized = twoFactorCode.value.trim()
  const valid = twoFactorMethod.value === 'totp'
    ? /^\d{6}$/.test(normalized)
    : /^[A-Z2-7]{4}(?:-[A-Z2-7]{4}){3}$/.test(normalized.toUpperCase())
  if (!valid) {
    twoFactorError.value = twoFactorMethod.value === 'totp'
      ? t('login.invalidAuthenticatorCode')
      : t('login.invalidRecoveryCode')
    return
  }
  loginLoading.value = true
  twoFactorError.value = ''
  try {
    const data = await verifyTwoFactorLogin({
      challengeToken: twoFactorChallenge.value,
      method: twoFactorMethod.value,
      code: normalized
    })
    if (data.success) {
      finishLogin(data)
      return
    }
    twoFactorError.value = data.message || t('login.twoFactorFailed')
    if (data.challengeExpired) {
      const message = twoFactorError.value
      resetTwoFactor()
      ElMessage.error(message)
    }
  } catch (e) {
    twoFactorError.value = t('login.networkError')
  }
  loginLoading.value = false
}

// 统一写入登录态并导航，确保普通登录和 2FA 登录行为一致
function finishLogin(data) {
  if (rememberMe.value) {
    safeSet('rememberMe', '1')
    safeSet('rememberedUsername', data.username || twoFactorUsername.value || loginForm.value.username)
  } else {
    safeRemove('rememberMe')
    safeRemove('rememberedUsername')
  }
  authStore.setAuth(data)
  router.push('/').finally(() => { loginLoading.value = false })
}

// 切换 TOTP/恢复码方式并清空上一个方式的输入和错误
async function setTwoFactorMethod(method) {
  twoFactorMethod.value = method
  twoFactorCode.value = ''
  twoFactorError.value = ''
  await nextTick()
  twoFactorInput.value?.focus()
}

// 按当前验证方式规范化输入，TOTP 仅保留数字，恢复码自动大写并分组
function normalizeTwoFactorInput() {
  twoFactorError.value = ''
  if (twoFactorMethod.value === 'totp') {
    twoFactorCode.value = twoFactorCode.value.replace(/\D/g, '').slice(0, 6)
    return
  }
  const raw = twoFactorCode.value.toUpperCase().replace(/[^A-Z2-7]/g, '').slice(0, 16)
  twoFactorCode.value = raw.match(/.{1,4}/g)?.join('-') || ''
}

// 放弃当前短期挑战并关闭弹窗，随后把焦点还给触发登录的按钮
async function resetTwoFactor() {
  twoFactorRequired.value = false
  twoFactorChallenge.value = ''
  twoFactorCode.value = ''
  twoFactorError.value = ''
  twoFactorMethod.value = 'totp'
  loginLoading.value = false
  await nextTick()
  loginSubmitButton.value?.focus()
}

// 验证请求进行中时禁止误关闭；空闲时支持关闭按钮、取消按钮和 Esc
function closeTwoFactorModal() {
  if (loginLoading.value) return
  resetTwoFactor()
}

// 将 Tab 焦点限制在 2FA 弹窗中，避免键盘焦点进入被遮罩的登录表单
function trapTwoFactorFocus(event) {
  const root = twoFactorModal.value
  if (!root) return
  const focusable = [...root.querySelectorAll(
    'button:not([disabled]), input:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )].filter(element => element.offsetParent !== null)
  if (!focusable.length) {
    event.preventDefault()
    root.focus()
    return
  }
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && (document.activeElement === first || document.activeElement === root)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (document.activeElement === last || document.activeElement === root)) {
    event.preventDefault()
    first.focus()
  }
}

// 提交注册表单并在注册成功后写入登录态
async function handleRegister() {
  if (!validateRegister()) return
  regLoading.value = true
  try {
    const data = await register({
      username: regForm.value.username,
      password: regForm.value.password,
      inviteCode: regForm.value.inviteCode,
      captchaId: registerConfig.value.captcha?.challengeId || '',
      captchaAnswer: regForm.value.captchaAnswer
    })
    if (data.success) {
      authStore.setAuth(data)
      // 同 handleLogin：跳转完成前保持 loading，防止重复提交
      router.push('/').finally(() => { regLoading.value = false })
      return
    }
    ElMessage.error(data.message || t('login.registerFailed'))
    if (registerConfig.value.captchaEnabled) {
      regForm.value.captchaAnswer = ''
      await loadRegisterConfig()
    }
  } catch (e) {
    ElMessage.error(t('login.networkError'))
  }
  regLoading.value = false
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
.captcha-input-wrap { display: flex; align-items: center; }
.captcha-refresh { flex: 0 0 auto; margin-left: 10px; }
/* 复选框键盘聚焦可见 */
.check-rail:focus-visible {
  outline: 2px solid var(--primary, #6366f1);
  outline-offset: 3px;
  border-radius: 4px;
}

/* 双重验证弹窗保持登录页原有的编辑式视觉，并与背景表单形成明确层级 */
.two-factor-overlay {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.64);
}
.two-factor-modal {
  width: min(440px, 100%);
  max-height: min(680px, calc(100dvh - 40px));
  overflow-y: auto;
  padding: 26px;
  border: 1px solid var(--border, #333);
  border-radius: var(--radius-large, 12px);
  background: var(--surface, #1d1d1c);
  color: var(--foreground, #eff1f4);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.35);
}
.two-factor-modal-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}
.two-factor-kicker {
  display: block;
  margin-bottom: 6px;
  color: var(--primary, #4285f4);
  font-family: var(--mono, monospace);
  font-size: 10px;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}
.two-factor-modal h2 {
  margin: 0;
  color: var(--foreground, #eff1f4);
  font-size: 22px;
  line-height: 1.25;
}
.two-factor-close {
  display: inline-flex;
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-small, 6px);
  color: var(--secondary-text, #949494);
  cursor: pointer;
}
.two-factor-close:hover {
  background: var(--elevated, #2e2e2e);
  color: var(--foreground, #eff1f4);
}
.two-factor-close:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}
.two-factor-help {
  margin: 14px 0 22px;
  color: var(--secondary-text, #949494);
  font-size: 13px;
  line-height: 1.7;
}
.two-factor-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.two-factor-methods {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.method-button {
  min-height: 40px;
  padding: 0 12px;
  border: 1px solid var(--border, #333);
  border-radius: var(--radius-small, 6px);
  background: transparent;
  color: var(--secondary-text, #949494);
  font-size: 12px;
  cursor: pointer;
}
.method-button.active {
  border-color: var(--primary, #4285f4);
  color: var(--foreground, #eff1f4);
  background: color-mix(in srgb, var(--primary, #4285f4) 12%, transparent);
}
.method-button:focus-visible,
.link-button:focus-visible {
  outline: 2px solid var(--primary, #6366f1);
  outline-offset: 3px;
}
.code-input {
  letter-spacing: 0.14em;
  font-variant-numeric: tabular-nums;
}
.two-factor-code-field {
  min-height: 92px;
}
.two-factor-error {
  min-height: 17px;
}
.two-factor-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 2px;
}
.two-factor-secondary,
.two-factor-primary {
  min-width: 104px;
  min-height: 42px;
  padding: 0 16px;
  border-radius: var(--radius-small, 6px);
  font-size: 12px;
  cursor: pointer;
}
.two-factor-secondary {
  border: 1px solid var(--border, #333);
  color: var(--secondary-text, #949494);
}
.two-factor-primary {
  background: var(--primary, #4285f4);
  color: #fff;
  font-weight: 600;
}
.two-factor-primary:hover {
  background: var(--primary-hover, #66a3ff);
}
.two-factor-secondary:disabled,
.two-factor-primary:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}
.link-button {
  padding: 0;
  border: 0;
  background: none;
  cursor: pointer;
}

@media (max-width: 480px) {
  .two-factor-overlay {
    align-items: flex-end;
    padding: 10px;
  }
  .two-factor-modal {
    width: 100%;
    max-height: calc(100dvh - 20px);
    padding: 22px 18px;
  }
  .two-factor-actions {
    display: grid;
    grid-template-columns: 1fr 1fr;
  }
  .two-factor-secondary,
  .two-factor-primary {
    min-width: 0;
  }
}
</style>
