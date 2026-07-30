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

    <div class="admin-card" v-loading="securityLoading" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">安全设置</h3>

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);width:150px;">IP绑定校验</span>
        <el-switch v-model="ipBindingEnabled" active-text="开启" inactive-text="关闭" />
        <el-button type="primary" @click="handleSaveSecurity" :loading="securitySaving">保存</el-button>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        开启后，登录时的 IP 会绑定到登录凭证，后续请求 IP 发生变更将强制下线并要求重新登录，可防止凭证被盗用。
        若用户网络环境 IP 频繁变化（如移动网络、公司出口多 IP）导致频繁被踢下线，可关闭此开关。保存后立即生效。
      </div>
    </div>

  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getStorageSettings, getQuotaSettings, setQuotaSettings, getSecuritySettings, setSecuritySettings } from '@/api/settings'

const loading = ref(false)
const settings = ref({ useSqlite: true, dbFileSize: '' })
const quotaLoading = ref(false)
const quotaSaving = ref(false)
const dailyChatLimit = ref(0)
const rateLimitPerMinute = ref(0)
const contextMaxMessages = ref(0)
const securityLoading = ref(false)
const securitySaving = ref(false)
const ipBindingEnabled = ref(true)

async function loadSettings() {
  loading.value = true
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
  loadQuota()
  loadSecurity()
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

// 加载安全设置
async function loadSecurity() {
  securityLoading.value = true
  try {
    const res = await getSecuritySettings()
    if (res?.success) {
      ipBindingEnabled.value = res.data?.ipBindingEnabled ?? true
    }
  } finally {
    securityLoading.value = false
  }
}

// 保存安全设置
async function handleSaveSecurity() {
  securitySaving.value = true
  try {
    const res = await setSecuritySettings({ ipBindingEnabled: ipBindingEnabled.value })
    if (res?.success) {
      ElMessage.success(res.message || '保存成功')
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    securitySaving.value = false
  }
}
</script>
