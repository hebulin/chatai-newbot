<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">10 / BACKUP · {{ $adminText('备份') }}</span>
        <h2>{{ $adminText('备份与恢复') }}</h2>
      </div>
      <div style="display:flex;gap:8px;align-items:center;">
        <el-checkbox :model-value="autoEnabled" @change="toggleAuto">{{ $adminText('每日自动备份（凌晨 3 点，保留最近 5 份）') }}</el-checkbox>
        <el-button type="primary" :loading="creating" @click="handleCreate">
          <el-icon><FolderAdd /></el-icon> {{ $adminText('立即备份') }}
        </el-button>
      </div>
    </div>

    <div class="admin-card">
      <el-alert type="warning" :closable="false" show-icon style="margin-bottom:14px;"
        :title="$adminText('备份包含数据库、上传资源与解密密钥，请妥善保管备份文件。恢复为高风险操作：需输入管理员密码确认，恢复前会自动创建当前状态的备份，恢复后所有登录态失效。')" />

      <el-table :data="backups" v-loading="loading" stripe border style="width:100%">
        <el-table-column prop="name" :label="$adminText('备份文件')" min-width="220" show-overflow-tooltip />
        <el-table-column :label="$adminText('大小')" width="110" align="right">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column :label="$adminText('创建时间')" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="$adminText('操作')" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="handleDownload(row)">{{ $adminText('下载') }}</el-button>
            <el-button size="small" text type="warning" @click="openRestore(row)">{{ $adminText('恢复') }}</el-button>
            <el-button size="small" text type="danger" @click="handleDelete(row)">{{ $adminText('删除') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!loading && backups.length === 0" :description="$adminText('暂无备份')" :image-size="60" />
    </div>

    <!-- 恢复确认弹窗：需输入管理员密码（重新认证） -->
    <el-dialog v-model="restoreDialog" :title="$adminText('恢复系统备份')" width="440px" :close-on-click-modal="false">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom:12px;"
        :title="$adminText('恢复将覆盖当前全部数据，期间系统进入维护状态；恢复前会自动创建当前状态备份。恢复完成后需要重新登录。')" />
      <div style="margin-bottom:8px;color:var(--ink-2);font-size:13px;">
        {{ $adminText('备份文件：') }}<b>{{ restoreTarget }}</b>
      </div>
      <el-input v-model="restorePassword" type="password" show-password :placeholder="$adminText('请输入当前管理员密码确认')" />
      <template #footer>
        <el-button @click="restoreDialog = false">{{ $adminText('取消') }}</el-button>
        <el-button type="danger" :loading="restoring" :disabled="!restorePassword" @click="handleRestore">
          {{ $adminText('确认恢复') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 上传备份恢复 -->
    <el-dialog v-model="uploadDialog" :title="$adminText('上传备份文件恢复')" width="440px" :close-on-click-modal="false">
      <el-alert type="error" :closable="false" show-icon style="margin-bottom:12px;"
        :title="$adminText('从本机选择备份 zip 文件上传并恢复。恢复前会自动创建当前状态备份。')" />
      <input ref="uploadFileRef" type="file" accept=".zip,application/zip" style="margin-bottom:12px;" />
      <el-input v-model="uploadPassword" type="password" show-password :placeholder="$adminText('请输入当前管理员密码确认')" />
      <template #footer>
        <el-button @click="uploadDialog = false">{{ $adminText('取消') }}</el-button>
        <el-button type="danger" :loading="restoring" :disabled="!uploadPassword" @click="handleUploadRestore">
          {{ $adminText('上传并恢复') }}
        </el-button>
      </template>
    </el-dialog>

    <div style="margin-top:14px;">
      <el-button @click="uploadDialog = true">{{ $adminText('上传备份文件恢复…') }}</el-button>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { FolderAdd } from '@element-plus/icons-vue'
import {
  listBackups, createBackup, downloadBackup, deleteBackup,
  restoreBackup, restoreBackupUpload, setBackupAutoEnabled
} from '@/api/backup'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import { adminApiText, adminText, getLocale } from '@/i18n'

const authStore = useAuthStore()
const router = useRouter()

const backups = ref([])
const loading = ref(false)
const creating = ref(false)
const autoEnabled = ref(true)
const restoreDialog = ref(false)
const restoreTarget = ref('')
const restorePassword = ref('')
const restoring = ref(false)
const uploadDialog = ref(false)
const uploadPassword = ref('')
const uploadFileRef = ref(null)
// keep-alive 下跳过与 onMounted 同发的首次激活刷新
let firstActivation = true

// 加载备份列表（silent=true 时不显示加载遮罩，供 keep-alive 激活静默刷新）
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await listBackups()
    if (res?.success) {
      backups.value = res.data || []
      autoEnabled.value = res.autoBackupEnabled !== false
    }
  } catch (e) {
    if (!silent) ElMessage.error(adminText('备份列表加载失败'))
  } finally {
    loading.value = false
  }
}

function formatSize(bytes) {
  const n = Number(bytes) || 0
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

function formatTime(ts) {
  if (!ts) return '-'
  return new Date(Number(ts)).toLocaleString(getLocale() === 'en' ? 'en-US' : 'zh-CN')
}

// 立即创建备份
async function handleCreate() {
  creating.value = true
  try {
    const res = await createBackup()
    if (res?.success) {
      ElMessage.success(adminText('备份已创建：{name}', { name: res.data.name }))
      await load()
    } else {
      ElMessage.error(adminApiText(res?.message, '备份失败'))
    }
  } catch (e) {
    ElMessage.error(adminText('备份失败'))
  } finally {
    creating.value = false
  }
}

// 下载备份文件
async function handleDownload(row) {
  try {
    const blob = await downloadBackup(row.name)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = row.name
    a.click()
    URL.revokeObjectURL(url)
  } catch (e) {
    ElMessage.error(adminText('下载失败'))
  }
}

// 删除备份（确认后执行）
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(adminText('确定删除备份「{name}」？删除后无法用于恢复。', { name: row.name }), adminText('删除备份'), {
      confirmButtonText: adminText('删除'),
      cancelButtonText: adminText('取消'),
      type: 'warning'
    })
  } catch { return }
  try {
    const res = await deleteBackup(row.name)
    if (res?.success) {
      ElMessage.success(adminText('备份已删除'))
      await load()
    } else {
      ElMessage.error(adminApiText(res?.message, '删除失败'))
    }
  } catch (e) {
    ElMessage.error(adminText('删除失败'))
  }
}

// 打开恢复确认弹窗
function openRestore(row) {
  restoreTarget.value = row.name
  restorePassword.value = ''
  restoreDialog.value = true
}

// 执行恢复：成功后登录态失效，跳转登录页
async function handleRestore() {
  restoring.value = true
  try {
    const res = await restoreBackup(restoreTarget.value, restorePassword.value)
    if (res?.success) {
      restoreDialog.value = false
      ElMessage.success(adminText('恢复完成，请重新登录'))
      authStore.logout()
      router.push('/login')
    } else {
      ElMessage.error(adminApiText(res?.message, '恢复失败'))
    }
  } catch (e) {
    ElMessage.error(getLocale() === 'en'
      ? adminText('恢复失败')
      : adminText('恢复失败：{message}', { message: e?.response?.data?.message || e.message || '' }))
  } finally {
    restoring.value = false
  }
}

// 上传备份文件并恢复
async function handleUploadRestore() {
  const file = uploadFileRef.value?.files?.[0]
  if (!file) {
    ElMessage.warning(adminText('请选择备份 zip 文件'))
    return
  }
  restoring.value = true
  try {
    const res = await restoreBackupUpload(file, uploadPassword.value)
    if (res?.success) {
      uploadDialog.value = false
      ElMessage.success(adminText('恢复完成，请重新登录'))
      authStore.logout()
      router.push('/login')
    } else {
      ElMessage.error(adminApiText(res?.message, '恢复失败'))
    }
  } catch (e) {
    ElMessage.error(getLocale() === 'en'
      ? adminText('恢复失败')
      : adminText('恢复失败：{message}', { message: e?.response?.data?.message || e.message || '' }))
  } finally {
    restoring.value = false
  }
}

// 切换每日自动备份开关
async function toggleAuto(val) {
  try {
    const res = await setBackupAutoEnabled(!!val)
    if (res?.success) {
      autoEnabled.value = !!val
      ElMessage.success(adminText(val ? '已开启每日自动备份' : '已关闭每日自动备份'))
    }
  } catch (e) {
    ElMessage.error(adminText('设置失败'))
  }
}

onMounted(() => load())
onActivated(() => {
  if (firstActivation) {
    firstActivation = false
    return
  }
  load(true)
})
</script>
