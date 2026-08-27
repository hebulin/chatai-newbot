<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">06 / SETTINGS · 设置</span>
        <h2>系统设置</h2>
      </div>
    </div>

    <div class="admin-card" v-loading="loading">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">存储模式</h3>

      <div style="display:flex;gap:32px;margin-bottom:20px;flex-wrap:wrap;">
        <div>
          <div style="font-size:12px;color:var(--ink-3);margin-bottom:4px;">当前模式</div>
          <div :style="{ fontSize:'15px', fontWeight:'600', color: settings.useSqlite ? '#10b981' : '#f59e0b' }">
            {{ settings.useSqlite ? 'SQLite 数据库' : 'JSON 文件' }}
          </div>
        </div>
        <div>
          <div style="font-size:12px;color:var(--ink-3);margin-bottom:4px;">数据库文件大小</div>
          <div style="font-size:15px;font-weight:600;color:var(--ink);">{{ settings.dbFileSize || '-' }}</div>
        </div>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        系统数据统一存储于 SQLite 数据库文件 (data/chatai.db)，包括用户、模型配置、使用记录与会话分享等。
      </div>
    </div>

    <div class="admin-card" v-loading="observabilityLoading" style="margin-top:20px;">
      <div class="observability-heading">
        <div>
          <h3 style="font-size:16px;font-weight:600;margin:0 0 4px;color:var(--ink);">运行状态</h3>
          <div class="observability-subtitle">进程启动后累计指标，用于快速定位错误、慢请求与聊天流拥塞。</div>
        </div>
        <el-button @click="loadObservability()">刷新</el-button>
      </div>
      <div class="observability-grid">
        <div class="metric-item"><span>运行时长</span><strong>{{ formatDuration(observability.uptimeSeconds) }}</strong></div>
        <div class="metric-item"><span>HTTP 请求</span><strong>{{ observability.requestCount ?? 0 }}</strong></div>
        <div class="metric-item"><span>服务端错误</span><strong>{{ observability.serverErrorCount ?? 0 }}</strong></div>
        <div class="metric-item"><span>慢请求</span><strong>{{ observability.slowRequestCount ?? 0 }}</strong></div>
        <div class="metric-item"><span>平均延迟</span><strong>{{ observability.averageLatencyMs ?? 0 }} ms</strong></div>
        <div class="metric-item"><span>活跃聊天流</span><strong>{{ observability.activeChats ?? 0 }}</strong></div>
        <div class="metric-item"><span>聊天失败</span><strong>{{ observability.chatFailureCount ?? 0 }} / {{ observability.chatRequestCount ?? 0 }}</strong></div>
        <div class="metric-item"><span>JVM 堆内存</span><strong>{{ formatBytes(observability.heapUsedBytes) }} / {{ formatBytes(observability.heapMaxBytes) }}</strong></div>
      </div>
    </div>

    <div class="admin-card" v-loading="healthLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">模型健康检查</h3>

      <div class="security-setting-row">
        <span class="security-setting-label">定时健康检查</span>
        <el-switch v-model="healthEnabled" active-text="开启" inactive-text="关闭" />
      </div>

      <div class="security-setting-row">
        <span class="security-setting-label">检查间隔</span>
        <el-input-number v-model="healthIntervalMinutes" :min="1" :max="10080" :step="30" style="width:160px;" />
        <span style="font-size:13px;color:var(--ink-3);">分钟</span>
        <el-button type="primary" @click="handleSaveHealth" :loading="healthSaving">保存</el-button>
      </div>

      <div style="font-size:13px;color:var(--ink-3);margin-bottom:10px;">检查项目（关闭的模型不参与定时连通测试）</div>
      <div class="health-model-list">
        <div v-for="m in healthModels" :key="m.id" class="health-model-row">
          <span class="health-model-name" :title="m.modelId">{{ m.displayName || m.modelId }}</span>
          <span class="health-model-provider">{{ m.providerName || m.providerId }}</span>
          <span v-if="!m.enabled" class="health-model-disabled">已禁用</span>
          <el-switch
            :model-value="m.healthCheckEnabled !== false"
            @change="(val) => toggleModelHealth(m, val)"
          />
        </div>
        <div v-if="healthModels.length === 0" style="font-size:13px;color:var(--ink-4);padding:8px 0;">暂无模型</div>
      </div>

      <div class="settings-note">
        定时任务按上述间隔对开启的模型执行连通测试（最小请求测连通 + 短生成测速），结果展示在"模型管理"的延迟/速度列。
        总开关与间隔保存后 1 分钟内生效，无需重启；已禁用的模型无论开关与否都不会被检查；模型管理中的手动"测试连接"不受此开关影响。
      </div>
    </div>

    <div class="admin-card" v-loading="quotaLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">调用限制</h3>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">每个普通用户每日最多可调用</span>
        <el-input-number v-model="dailyChatLimit" :min="0" :max="100000" :step="10" style="width:160px;" />
        <span style="font-size:13px;color:var(--ink-3);">次（填 0 表示不限制）</span>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">每个普通用户每日 Token 上限</span>
        <el-input-number v-model="dailyTokenLimit" :min="0" :max="10000000000" :step="10000" style="width:200px;" />
        <span style="font-size:13px;color:var(--ink-3);">Token（0 表示不限制）</span>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">每个普通用户每日预算</span>
        <el-input-number v-model="dailyCostLimitCny" :min="0" :max="100000000" :precision="2" :step="10" style="width:200px;" />
        <span style="font-size:13px;color:var(--ink-3);">人民币元（0 表示不限制）</span>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">每个普通用户每分钟最多可发起</span>
        <el-input-number v-model="rateLimitPerMinute" :min="0" :max="10000" :step="1" style="width:160px;" />
        <span style="font-size:13px;color:var(--ink-3);">次（填 0 表示不限制）</span>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">上下文最多携带消息条数</span>
        <el-input-number v-model="contextMaxMessages" :min="0" :max="1000" :step="2" style="width:160px;" />
        <span style="font-size:13px;color:var(--ink-3);">条（填 0 表示不限制）</span>
        <el-button type="primary" @click="handleSaveQuota" :loading="quotaSaving">保存</el-button>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">历史摘要</span>
        <el-switch v-model="contextSummaryEnabled" />
        <span style="font-size:13px;color:var(--ink-3);">长对话超出上下文预算时自动生成历史摘要注入（失败时降级为截断，摘要调用按计费规则记录）</span>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        以上限制均仅对非管理员用户生效。每日调用达上限后次日自动恢复；每分钟限流用于防止短时频繁请求；上下文条数限制只保留最近若干条消息发送给模型，可降低 Token 消耗。单个用户可在“用户管理”中单独设置每日限额（优先于全局配额）。
      </div>
    </div>

    <div class="admin-card" v-loading="billingLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">计费与币种</h3>
      <div class="billing-row">
        <span class="billing-label">默认展示</span>
        <el-radio-group v-model="billing.displayMode">
          <el-radio-button value="token">Token</el-radio-button>
          <el-radio-button value="currency">金额</el-radio-button>
        </el-radio-group>
      </div>
      <div class="billing-row">
        <span class="billing-label">默认币种</span>
        <el-select v-model="billing.defaultCurrency" style="width:220px;">
          <el-option v-for="c in billing.currencies" :key="c.code" :label="`${c.name} (${c.code})`" :value="c.code" />
        </el-select>
      </div>
      <div class="currency-table">
        <div v-for="(currency, index) in billing.currencies" :key="currency.code || index" class="currency-row">
          <el-input v-model="currency.code" placeholder="USD" :disabled="currency.code === 'CNY'" maxlength="8" style="width:100px;" />
          <el-input v-model="currency.name" placeholder="美元" :disabled="currency.code === 'CNY'" style="width:150px;" />
          <el-input v-model="currency.symbol" placeholder="$" :disabled="currency.code === 'CNY'" style="width:90px;" />
          <span class="currency-rate-prefix">1 CNY =</span>
          <el-input-number v-model="currency.rate" :min="0.000001" :precision="6" :step="0.01" :disabled="currency.code === 'CNY'" style="width:160px;" />
          <span class="currency-code-label">{{ currency.code || '币种' }}</span>
          <el-button v-if="currency.code !== 'CNY'" type="danger" plain @click="removeCurrency(index)">删除</el-button>
        </div>
      </div>
      <div style="display:flex;gap:10px;margin-top:16px;">
        <el-button @click="addCurrency">新增币种</el-button>
        <el-button type="primary" :loading="billingSaving" @click="handleSaveBilling">保存计费设置</el-button>
      </div>
      <div class="settings-note">
        人民币是成本基础币种且汇率固定为 1。其他币种必须配置“1 人民币兑换多少目标币种”；历史数据保存人民币成本，展示时按当前汇率实时换算。
      </div>
    </div>

    <div class="admin-card" v-loading="securityLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">安全设置</h3>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">IP绑定校验</span>
        <el-switch v-model="ipBindingEnabled" active-text="开启" inactive-text="关闭" />
      </div>

      <div class="security-setting-row">
        <span class="security-setting-label">开放用户注册</span>
        <el-switch v-model="registrationEnabled" active-text="开启" inactive-text="关闭" />
      </div>

      <div class="security-setting-row">
        <span class="security-setting-label">注册验证码</span>
        <el-switch v-model="registrationCaptchaEnabled" active-text="开启" inactive-text="关闭" />
      </div>

      <div class="security-setting-row">
        <span class="security-setting-label">注册邀请码</span>
        <el-input v-model="inviteCode" type="password" show-password style="width:280px" :placeholder="inviteCodeConfigured ? '已配置，留空表示保持不变' : '留空表示不限制邀请码'" />
        <el-checkbox v-if="inviteCodeConfigured" v-model="clearInviteCode">清除现有邀请码</el-checkbox>
      </div>

      <div class="security-setting-row security-setting-row-top">
        <span class="security-setting-label">Bot SVG 头像</span>
        <el-input v-model="botAvatarSvg" type="textarea" :rows="6" maxlength="20000" show-word-limit placeholder="粘贴完整的 <svg>...</svg> 代码；留空使用默认头像" style="max-width:620px" />
      </div>

      <el-button type="primary" @click="handleSaveSecurity" :loading="securitySaving">保存安全设置</el-button>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        开启后，登录时的 IP 会绑定到登录凭证，后续请求 IP 发生变更将强制下线并要求重新登录，可防止凭证被盗用。
        若用户网络环境 IP 频繁变化（如移动网络、公司出口多 IP）导致频繁被踢下线，可关闭此开关。保存后立即生效。
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { getStorageSettings, getQuotaSettings, setQuotaSettings, getSecuritySettings, setSecuritySettings, getBillingSettings, setBillingSettings, getObservability, getHealthCheckSettings, setHealthCheckSettings } from '@/api/settings'
import { getModels, updateModel } from '@/api/models'

const loading = ref(false)
const settings = ref({ useSqlite: true, dbFileSize: '' })
const observabilityLoading = ref(false)
const observability = ref({})
const quotaLoading = ref(false)
const quotaSaving = ref(false)
const dailyChatLimit = ref(0)
const dailyTokenLimit = ref(0)
const dailyCostLimitCny = ref(0)
const rateLimitPerMinute = ref(0)
const contextMaxMessages = ref(0)
// 历史摘要开关（长对话超预算时自动生成摘要注入）
const contextSummaryEnabled = ref(true)
const securityLoading = ref(false)
const securitySaving = ref(false)
const ipBindingEnabled = ref(true)
const registrationEnabled = ref(true)
const registrationCaptchaEnabled = ref(false)
const inviteCodeConfigured = ref(false)
const inviteCode = ref('')
const clearInviteCode = ref(false)
const botAvatarSvg = ref('')
const billingLoading = ref(false)
const billingSaving = ref(false)
const billing = ref({ displayMode: 'token', defaultCurrency: 'CNY', currencies: [{ code: 'CNY', name: '人民币', symbol: '¥', rate: 1 }] })
const healthLoading = ref(false)
const healthSaving = ref(false)
const healthEnabled = ref(true)
const healthIntervalMinutes = ref(360)
const healthModels = ref([])

// 加载模型健康检查设置与检查项目列表；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用）
async function loadHealth(silent = false) {
  if (!silent) healthLoading.value = true
  try {
    const [sRes, mRes] = await Promise.all([getHealthCheckSettings(), getModels()])
    if (sRes?.success) {
      healthEnabled.value = sRes.data?.enabled ?? true
      healthIntervalMinutes.value = sRes.data?.intervalMinutes ?? 360
    }
    if (mRes?.success) healthModels.value = mRes.data || []
  } finally {
    healthLoading.value = false
  }
}

// 保存健康检查总开关与检查间隔
async function handleSaveHealth() {
  healthSaving.value = true
  try {
    const res = await setHealthCheckSettings({
      enabled: healthEnabled.value,
      intervalMinutes: healthIntervalMinutes.value ?? 360
    })
    if (res?.success) {
      ElMessage.success(res.message || '保存成功')
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    healthSaving.value = false
  }
}

// 切换单个模型是否参与健康检查（复用模型更新接口，行内数据含脱敏 Key 时后端自动保留原 Key）
async function toggleModelHealth(m, val) {
  const res = await updateModel(m.id, { ...m, healthCheckEnabled: val })
  if (res?.success) {
    m.healthCheckEnabled = val
    ElMessage.success(val ? `已开启「${m.displayName || m.modelId}」健康检查` : `已关闭「${m.displayName || m.modelId}」健康检查`)
  } else {
    ElMessage.error(res?.message || '保存失败')
    await loadHealth(true)
  }
}

// 加载存储设置；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadSettings(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await getStorageSettings()
    if (res?.success) {
      settings.value = res.data || { useSqlite: true, dbFileSize: '' }
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadSettings()
  loadObservability()
  loadQuota()
  loadSecurity()
  loadBilling()
  loadHealth()
})

// keep-alive 缓存下再次进入本页时静默刷新各分区设置；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadSettings(true)
  loadObservability(true)
  loadQuota(true)
  loadSecurity(true)
  loadBilling(true)
  loadHealth(true)
})

// 加载当前进程的运行指标；静默刷新时不显示整卡遮罩
async function loadObservability(silent = false) {
  if (!silent) observabilityLoading.value = true
  try {
    const res = await getObservability()
    if (res?.success) observability.value = res.data || {}
  } finally {
    observabilityLoading.value = false
  }
}

// 将秒数格式化为适合后台概览的紧凑时长
function formatDuration(seconds) {
  const value = Math.max(0, Number(seconds || 0))
  const days = Math.floor(value / 86400)
  const hours = Math.floor((value % 86400) / 3600)
  const minutes = Math.floor((value % 3600) / 60)
  if (days) return `${days}天 ${hours}小时`
  if (hours) return `${hours}小时 ${minutes}分钟`
  return `${minutes}分钟`
}

// 将字节数格式化为 MB/GB，避免后台展示难读的长整数
function formatBytes(bytes) {
  const value = Math.max(0, Number(bytes || 0))
  if (value >= 1024 ** 3) return `${(value / 1024 ** 3).toFixed(1)} GB`
  return `${(value / 1024 ** 2).toFixed(1)} MB`
}

// 加载每日配额设置；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用）
async function loadQuota(silent = false) {
  if (!silent) quotaLoading.value = true
  try {
    const res = await getQuotaSettings()
    if (res?.success) {
      dailyChatLimit.value = res.data?.dailyChatLimit ?? 0
      dailyTokenLimit.value = res.data?.dailyTokenLimit ?? 0
      dailyCostLimitCny.value = res.data?.dailyCostLimitCny ?? 0
      rateLimitPerMinute.value = res.data?.rateLimitPerMinute ?? 0
      contextMaxMessages.value = res.data?.contextMaxMessages ?? 0
      contextSummaryEnabled.value = res.data?.contextSummaryEnabled !== false
    }
  } finally {
    quotaLoading.value = false
  }
}

// 保存每日配额设置
async function handleSaveQuota() {
  quotaSaving.value = true
  try {
    const res = await setQuotaSettings({
      dailyChatLimit: dailyChatLimit.value ?? 0,
      dailyTokenLimit: dailyTokenLimit.value ?? 0,
      dailyCostLimitCny: dailyCostLimitCny.value ?? 0,
      rateLimitPerMinute: rateLimitPerMinute.value ?? 0,
      contextMaxMessages: contextMaxMessages.value ?? 0,
      contextSummaryEnabled: contextSummaryEnabled.value
    })
    if (res?.success) {
      ElMessage.success({ message: (res.messages || [res.message || '保存成功']).join('；'), duration: 5000, showClose: true })
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    quotaSaving.value = false
  }
}

// 加载安全设置；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用）
async function loadSecurity(silent = false) {
  if (!silent) securityLoading.value = true
  try {
    const res = await getSecuritySettings()
    if (res?.success) {
      ipBindingEnabled.value = res.data?.ipBindingEnabled ?? true
      registrationEnabled.value = res.data?.registrationEnabled ?? true
      registrationCaptchaEnabled.value = res.data?.registrationCaptchaEnabled ?? false
      inviteCodeConfigured.value = res.data?.inviteCodeConfigured ?? false
      inviteCode.value = ''
      clearInviteCode.value = false
      botAvatarSvg.value = res.data?.botAvatarSvg || ''
    }
  } finally {
    securityLoading.value = false
  }
}

// 保存安全设置
async function handleSaveSecurity() {
  securitySaving.value = true
  try {
    const payload = {
      ipBindingEnabled: ipBindingEnabled.value,
      registrationEnabled: registrationEnabled.value,
      registrationCaptchaEnabled: registrationCaptchaEnabled.value,
      clearInviteCode: clearInviteCode.value,
      botAvatarSvg: botAvatarSvg.value
    }
    if (inviteCode.value.trim()) payload.inviteCode = inviteCode.value.trim()
    const res = await setSecuritySettings(payload)
    if (res?.success) {
      ElMessage.success(res.message || '保存成功')
      await loadSecurity(true)
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    securitySaving.value = false
  }
}

// 加载计费与币种配置；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用）
async function loadBilling(silent = false) {
  if (!silent) billingLoading.value = true
  try {
    const res = await getBillingSettings()
    if (res?.success && res.data) billing.value = res.data
  } finally {
    billingLoading.value = false
  }
}

// 新增一个待配置币种行
function addCurrency() {
  billing.value.currencies.push({ code: '', name: '', symbol: '', rate: 1 })
}

// 删除指定币种并在必要时回退默认币种
function removeCurrency(index) {
  const [removed] = billing.value.currencies.splice(index, 1)
  if (removed?.code === billing.value.defaultCurrency) billing.value.defaultCurrency = 'CNY'
}

// 保存计费配置，提交前规范化币种代码
async function handleSaveBilling() {
  billingSaving.value = true
  try {
    const payload = {
      displayMode: billing.value.displayMode,
      defaultCurrency: String(billing.value.defaultCurrency || 'CNY').toUpperCase(),
      currencies: billing.value.currencies.map(c => ({ ...c, code: String(c.code || '').trim().toUpperCase() }))
    }
    const res = await setBillingSettings(payload)
    if (res?.success) {
      billing.value = res.data
      ElMessage.success(res.message || '保存成功')
    } else ElMessage.error(res?.message || '保存失败')
  } finally {
    billingSaving.value = false
  }
}
</script>

<style scoped>
.billing-row,.currency-row{display:flex;align-items:center;gap:12px;margin-bottom:14px;flex-wrap:wrap}.billing-label{width:150px;font-size:13px;color:var(--ink-3)}.currency-table{padding:14px;border:1px solid var(--line);border-radius:8px;background:var(--paper-2)}.currency-rate-prefix,.currency-code-label{font-size:12px;color:var(--ink-3)}.settings-note{margin-top:16px;font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line)}
.security-setting-row{display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap}.security-setting-row-top{align-items:flex-start}.security-setting-label{width:150px;font-size:13px;color:var(--ink-3);flex:0 0 auto}
.observability-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start;margin-bottom:16px}.observability-subtitle{font-size:12px;color:var(--ink-3)}.observability-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px}.metric-item{display:flex;flex-direction:column;gap:6px;padding:14px;border:1px solid var(--line);border-radius:8px;background:var(--paper-2)}.metric-item span{font-size:12px;color:var(--ink-3)}.metric-item strong{font-size:16px;color:var(--ink);font-weight:600}
.health-model-list{border:1px solid var(--line);border-radius:8px;background:var(--paper-2);padding:6px 14px;max-height:320px;overflow-y:auto}.health-model-row{display:flex;align-items:center;gap:12px;padding:8px 0;border-bottom:1px solid var(--line)}.health-model-row:last-child{border-bottom:none}.health-model-name{font-size:13px;color:var(--ink);overflow:hidden;text-overflow:ellipsis;white-space:nowrap;max-width:260px}.health-model-provider{font-size:12px;color:var(--ink-3)}.health-model-disabled{font-size:12px;color:#f59e0b}.health-model-row .el-switch{margin-left:auto}
</style>
