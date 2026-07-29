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

      <div style="display:flex;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <el-button @click="handleMigrate" :loading="migrating">一键迁移到 SQLite</el-button>
        <el-button type="primary" @click="handleToggle" :loading="switching">
          {{ settings.useSqlite ? '切换到 JSON 文件' : '切换到 SQLite' }}
        </el-button>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        <b style="color:#f56c6c">重要：切换到 SQLite 前请务必先「一键迁移」。</b>
        若未迁移直接切换，SQLite 中可能没有用户账号，将导致所有人（含管理员）无法登录。页面已强制「先迁移、后切换」。<br>
        切换后新数据将写入 SQLite 数据库文件 (data/chatai.db)。<br>
        切换回 JSON 模式后，将读取 JSON 文件中的旧数据（迁移后新增的数据不会回写）。
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

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        以上限制均仅对非管理员用户生效。每日调用达上限后次日自动恢复；每分钟限流用于防止短时频繁请求；上下文条数限制只保留最近若干条消息发送给模型，可降低 Token 消耗。单个用户可在“用户管理”中单独设置每日限额（优先于全局配额）。
      </div>
    </div>

    <div class="admin-card" v-loading="wsLoading" style="margin-top:20px;">
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

    <div class="admin-card" v-loading="annLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">系统公告</h3>

      <el-input
        v-model="annContent"
        type="textarea"
        :rows="5"
        maxlength="5000"
        show-word-limit
        placeholder="输入公告内容，登录用户进入聊天页时会弹窗展示；清空内容并保存即可下线公告"
        style="margin-bottom:12px;"
      />

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <el-button type="primary" @click="handleSaveAnnouncement" :loading="annSaving">发布公告</el-button>
        <el-button @click="handleClearAnnouncement" :loading="annSaving" :disabled="!annHasContent">清除公告</el-button>
        <span v-if="annUpdatedAt" style="font-size:13px;color:var(--ink-3);">最后更新：{{ annUpdatedAt }}</span>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        公告发布后，用户登录进入聊天页面时会弹窗展示一次；重新发布（更新时间变化）后会再次提醒已读过的用户。
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getStorageSettings, setStorageMode, migrateData, getQuotaSettings, setQuotaSettings, getWebSearchSettings, setWebSearchSettings, testWebSearch, getAnnouncementSettings, setAnnouncementSettings } from '@/api/settings'

const loading = ref(false)
const migrating = ref(false)
const switching = ref(false)
const settings = ref({ useSqlite: false, dbFileSize: '' })
const quotaLoading = ref(false)
const quotaSaving = ref(false)
const dailyChatLimit = ref(0)
const rateLimitPerMinute = ref(0)
const contextMaxMessages = ref(0)

async function loadSettings() {
  loading.value = true
  try {
    const res = await getStorageSettings()
    if (res?.success) {
      settings.value = res.data || { useSqlite: false, dbFileSize: '' }
    }
  } finally {
    loading.value = false
  }
}

// 一键迁移
async function handleMigrate() {
  try {
    await ElMessageBox.confirm(
      '确定将全部 JSON 数据迁移到 SQLite 吗？此操作不会删除 JSON 文件。聊天记录将以 JSON 文件内容为准写入（可重复执行，用于修复历史数据）。',
      '一键迁移到 SQLite',
      { type: 'warning' }
    )
  } catch { return }

  migrating.value = true
  try {
    const res = await migrateData()
    if (res?.success) {
      ElMessage.success(res.message || '迁移完成')
      await loadSettings()
    } else {
      ElMessage.error(res?.message || '迁移失败')
    }
  } finally {
    migrating.value = false
  }
}

// 切换存储模式
async function handleToggle() {
  if (!settings.value.useSqlite) {
    // JSON → SQLite：必须先迁移再切换
    try {
      await ElMessageBox.confirm(
        '切换到 SQLite 前必须先迁移数据。若未迁移直接切换，SQLite 中可能没有用户账号，将导致所有人（含管理员）无法登录。点击「先迁移再切换」将自动先执行一键迁移，成功后再切换到 SQLite（推荐且安全）。',
        '切换到 SQLite（需先迁移）',
        { confirmButtonText: '先迁移再切换', cancelButtonText: '取消', type: 'warning' }
      )
    } catch { return }

    switching.value = true
    try {
      // 第一步：迁移
      const migRes = await migrateData()
      if (!migRes?.success) {
        ElMessage.error((migRes?.message || '迁移失败') + '，已取消切换')
        return
      }
      // 第二步：切换
      const swRes = await setStorageMode(true)
      if (swRes?.success) {
        ElMessage.success('已迁移并切换到 SQLite')
        await loadSettings()
      } else {
        ElMessage.error(swRes?.message || '切换失败')
      }
    } finally {
      switching.value = false
    }
  } else {
    // SQLite → JSON
    try {
      await ElMessageBox.confirm(
        '确定切换回 JSON 文件存储吗？切换后将读取 JSON 文件中的旧数据。',
        '切换存储模式',
        { type: 'warning' }
      )
    } catch { return }

    switching.value = true
    try {
      const res = await setStorageMode(false)
      if (res?.success) {
        ElMessage.success(res.message || '切换成功')
        await loadSettings()
      } else {
        ElMessage.error(res?.message || '切换失败')
      }
    } finally {
      switching.value = false
    }
  }
}

onMounted(() => {
  loadSettings()
  loadQuota()
  loadWebSearch()
  loadAnnouncement()
})

// 加载每日配额设置
async function loadQuota() {
  quotaLoading.value = true
  try {
    const res = await getQuotaSettings()
    if (res?.success) {
      dailyChatLimit.value = res.data?.dailyChatLimit ?? 0
      rateLimitPerMinute.value = res.data?.rateLimitPerMinute ?? 0
      contextMaxMessages.value = res.data?.contextMaxMessages ?? 0
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
      rateLimitPerMinute: rateLimitPerMinute.value ?? 0,
      contextMaxMessages: contextMaxMessages.value ?? 0
    })
    if (res?.success) {
      ElMessage.success(res.message || '保存成功')
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    quotaSaving.value = false
  }
}

// ===== 联网搜索（Tavily） =====
const wsLoading = ref(false)
const wsSaving = ref(false)
const wsTesting = ref(false)
const wsEnabled = ref(false)
const wsHasKey = ref(false)
const wsApiKeyMasked = ref('')
const wsApiKey = ref('')

// 加载联网搜索设置
async function loadWebSearch() {
  wsLoading.value = true
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

// ===== 系统公告 =====
const annLoading = ref(false)
const annSaving = ref(false)
const annContent = ref('')
const annUpdatedAt = ref('')
// 服务端当前是否存在公告（控制“清除公告”按钮可用性）
const annHasContent = ref(false)

// 加载公告设置
async function loadAnnouncement() {
  annLoading.value = true
  try {
    const res = await getAnnouncementSettings()
    if (res?.success) {
      annContent.value = res.data?.content || ''
      annUpdatedAt.value = res.data?.updatedAt || ''
      annHasContent.value = !!(res.data?.content || '').trim()
    }
  } finally {
    annLoading.value = false
  }
}

// 发布公告
async function handleSaveAnnouncement() {
  if (!annContent.value.trim()) {
    ElMessage.warning('公告内容不能为空，如需下线公告请点“清除公告”')
    return
  }
  annSaving.value = true
  try {
    const res = await setAnnouncementSettings(annContent.value.trim())
    if (res?.success) {
      ElMessage.success(res.message || '公告已发布')
      await loadAnnouncement()
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    annSaving.value = false
  }
}

// 清除公告
async function handleClearAnnouncement() {
  try {
    await ElMessageBox.confirm('确定清除当前公告吗？清除后用户将不再看到公告弹窗。', '清除公告', { type: 'warning' })
  } catch { return }
  annSaving.value = true
  try {
    const res = await setAnnouncementSettings('')
    if (res?.success) {
      ElMessage.success(res.message || '公告已清除')
      annContent.value = ''
      await loadAnnouncement()
    } else {
      ElMessage.error(res?.message || '清除失败')
    }
  } finally {
    annSaving.value = false
  }
}
</script>
