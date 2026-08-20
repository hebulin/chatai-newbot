<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">07 / WEB SEARCH · 联网</span>
        <h2>联网功能配置</h2>
      </div>
    </div>

    <div class="admin-card" v-loading="wsLoading">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">联网搜索（Tavily）</h3>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">联网搜索总开关</span>
        <el-switch v-model="wsEnabled" :disabled="!wsHasKey && !wsApiKey" />
        <span style="font-size:13px;color:var(--ink-3);">{{ wsEnabled ? '已开启：聊天输入框将展示“联网”按钮' : '已关闭：聊天页面不显示联网按钮' }}</span>
      </div>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">Tavily API Key</span>
        <el-input
          v-model="wsApiKey"
          :placeholder="wsHasKey ? wsApiKeyMasked + '（已配置，留空表示不修改）' : '请输入 tvly- 开头的 API Key'"
          clearable
          style="width:360px;"
        />
        <el-button @click="handleTestWebSearch" :loading="wsTesting" :disabled="!wsHasKey && !wsApiKey">测试连接</el-button>
        <el-button type="primary" @click="handleSaveWebSearch" :loading="wsSaving">保存</el-button>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        开启后，用户在聊天输入框可开启“联网”，回答前先通过 Tavily 检索实时网络信息并作为参考资料提供给模型。
        <b style="color:#f56c6c">必须先配置 API Key 才能开启总开关；关闭总开关后聊天页面的联网按钮会隐藏。</b>
        API Key 可在 <a href="https://tavily.com" target="_blank" style="color:var(--accent);">tavily.com</a> 免费申请，保存后仅显示掩码。
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage } from 'element-plus'
import { getWebSearchSettings, setWebSearchSettings, testWebSearch } from '@/api/settings'

const wsLoading = ref(false)
const wsSaving = ref(false)
const wsTesting = ref(false)
const wsEnabled = ref(false)
const wsHasKey = ref(false)
const wsApiKeyMasked = ref('')
const wsApiKey = ref('')

onMounted(() => {
  loadWebSearch()
})

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadWebSearch(true)
})

// 加载联网搜索设置；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadWebSearch(silent = false) {
  if (!silent) wsLoading.value = true
  try {
    const res = await getWebSearchSettings()
    if (res?.success) {
      wsEnabled.value = !!res.data?.enabled
      wsHasKey.value = !!res.data?.hasKey
      wsApiKeyMasked.value = res.data?.apiKeyMasked || ''
    }
  } finally {
    wsLoading.value = false
  }
}

// 保存联网搜索设置（Key 留空表示不修改）
async function handleSaveWebSearch() {
  if (wsEnabled.value && !wsHasKey.value && !wsApiKey.value.trim()) {
    ElMessage.warning('请先配置 Tavily API Key 再开启联网搜索')
    return
  }
  wsSaving.value = true
  try {
    const res = await setWebSearchSettings({
      enabled: wsEnabled.value,
      apiKey: wsApiKey.value.trim()
    })
    if (res?.success) {
      ElMessage.success(res.message || '保存成功')
      wsApiKey.value = ''
      await loadWebSearch()
    } else {
      ElMessage.error(res?.message || '保存失败')
      await loadWebSearch()
    }
  } finally {
    wsSaving.value = false
  }
}

// 测试 Tavily 连通性（优先用输入框里的新 Key，空则用已保存的 Key）
async function handleTestWebSearch() {
  wsTesting.value = true
  try {
    const res = await testWebSearch(wsApiKey.value.trim())
    if (res?.success) {
      ElMessage.success(res.message || '连接成功')
    } else {
      ElMessage.error(res?.message || '连接失败')
    }
  } finally {
    wsTesting.value = false
  }
}
</script>
