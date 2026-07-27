<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">05 / SETTINGS · 设置</span>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getStorageSettings, setStorageMode, migrateData } from '@/api/settings'

const loading = ref(false)
const migrating = ref(false)
const switching = ref(false)
const settings = ref({ useSqlite: false, dbFileSize: '' })

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

onMounted(loadSettings)
</script>
