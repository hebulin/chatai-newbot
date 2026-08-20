<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">09 / AUDIT · 审计</span>
        <h2>审计日志</h2>
      </div>
    </div>

    <div class="admin-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-input v-model="filterUser" placeholder="用户名模糊查询" clearable style="width:180px" @change="reload" />
        <el-select v-model="filterAction" placeholder="全部操作" clearable filterable style="width:200px" @change="reload">
          <el-option v-for="a in actions" :key="a" :label="actionText(a)" :value="a" />
        </el-select>
        <el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD"
                        range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                        style="width:260px" @change="reload" />
        <el-button @click="handleReset">重置</el-button>
        <el-button type="danger" plain :loading="resetting" style="margin-left:auto" @click="handleResetAudit">重置审计日志</el-button>
      </div>

      <!-- 日志表格（服务端分页） -->
      <el-table :data="logs" v-loading="loading" stripe border style="width:100%">
        <el-table-column prop="timestamp" label="时间" width="170" show-overflow-tooltip />
        <el-table-column prop="username" label="用户" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="row.username ? '' : 'color:var(--ink-3);'">{{ row.username || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <span class="status-badge" :class="isDangerAction(row.action) ? 'status-disabled' : 'status-enabled'">
              {{ actionText(row.action) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="detail" label="详情" min-width="240" show-overflow-tooltip />
        <el-table-column prop="ip" label="来源IP" width="140" show-overflow-tooltip />
      </el-table>
      <!-- 统一分页组件（服务端分页；审计日志每页选项为 20/50/100） -->
      <AdminPager v-model:page="page" v-model:page-size="pageSize"
        :total="total" :total-pages="totalPages"
        :size-options="[20, 50, 100]"
        @size-change="reload" @page-change="load" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getAuditLogs, getAuditActions, resetAuditLogs } from '@/api/audit'
import AdminPager from '@/components/admin/AdminPager.vue'

const loading = ref(false)
const resetting = ref(false)
const logs = ref([])
const actions = ref([])
const filterUser = ref('')
const filterAction = ref('')
const dateRange = ref(null)

const page = ref(1)
const pageSize = ref(20)
const total = ref(0)
const totalPages = ref(1)

// 操作类型编码 → 中文名（未知编码原样展示）
const ACTION_TEXT = {
  'login': '登录成功',
  'login.fail': '登录失败',
  'logout': '退出登录',
  'register': '注册账号',
  'password.change': '修改密码',
  'user.add': '新增用户',
  'user.update': '更新用户',
  'user.delete': '删除用户',
  'user.batchDelete': '批量删除用户',
  'user.permissions': '用户模型权限',
  'model.add': '新增模型',
  'model.update': '更新模型',
  'model.delete': '删除模型',
  'model.batchDelete': '批量删除模型',
  'model.batchAdd': '批量接入模型',
  'model.setDefault': '设置默认模型',
  'model.clearDefault': '取消默认模型',
  'settings.quota': '配额设置',
  'settings.websearch': '联网搜索设置',
  'settings.security': '安全设置',
  'announcement.publish': '发布公告',
  'announcement.update': '更新公告',
  'announcement.offline': '下线公告',
  'announcement.delete': '删除公告',
  'share.batchDelete': '批量删除分享',
  'provider.rename': '修改厂商',
  'model.test': '模型连通测试',
  'settings.websearch.test': '联网连通测试',
  'audit.reset': '重置审计日志'
}
function actionText(a) {
  return ACTION_TEXT[a] || a
}
// 删除类/失败类操作用红色标识
function isDangerAction(a) {
  return a && (a.includes('delete') || a.includes('Delete') || a.endsWith('.fail'))
}

// 加载审计日志（服务端分页）；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const params = { page: page.value, size: pageSize.value }
    if (filterUser.value) params.username = filterUser.value
    if (filterAction.value) params.action = filterAction.value
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res = await getAuditLogs(params)
    if (res?.success) {
      logs.value = res.data || []
      total.value = res.total || 0
      totalPages.value = res.totalPages || 1
      page.value = res.page || 1
    } else {
      ElMessage.error(res?.message || '加载失败')
    }
  } catch (e) {
    ElMessage.error('加载审计日志失败')
  } finally {
    loading.value = false
  }
}

// 筛选条件变化：回到第一页重新加载
function reload() {
  page.value = 1
  load()
}

function handleReset() {
  filterUser.value = ''
  filterAction.value = ''
  dateRange.value = null
  reload()
}

// 重置审计日志：二次确认 + 管理员密码校验，重置后仅保留一条重置记录
async function handleResetAudit() {
  try {
    await ElMessageBox.confirm(
      '重置将永久清空全部审计日志且不可恢复，清空后仅保留本次重置的一条记录。确定继续吗？',
      '重置审计日志',
      { type: 'warning', confirmButtonText: '继续', cancelButtonText: '取消' }
    )
  } catch { return }

  let password
  try {
    const { value } = await ElMessageBox.prompt(
      '请输入当前管理员账号密码以确认重置：',
      '验证管理员密码',
      {
        inputType: 'password',
        confirmButtonText: '确认重置',
        cancelButtonText: '取消',
        inputPlaceholder: '管理员密码',
        inputValidator: v => (v && v.trim()) ? true : '密码不能为空'
      }
    )
    password = value
  } catch { return }

  resetting.value = true
  try {
    const res = await resetAuditLogs(password)
    if (res?.success) {
      ElMessage.success(res.message || '审计日志已重置')
      reload()
      loadActions()
    } else {
      ElMessage.error(res?.message || '重置失败')
    }
  } catch {
    ElMessage.error('重置审计日志失败')
  } finally {
    resetting.value = false
  }
}

async function loadActions() {
  try {
    const res = await getAuditActions()
    if (res?.success) actions.value = res.data || []
  } catch { /* 下拉加载失败不影响主流程 */ }
}

onMounted(() => {
  load()
  loadActions()
})

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  load(true)
  loadActions()
})
</script>
