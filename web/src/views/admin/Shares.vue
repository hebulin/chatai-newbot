<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">05 / SHARES · {{ $adminText('分享') }}</span>
        <h2>{{ $adminText('分享管理') }}</h2>
      </div>
      <div style="display:flex;gap:8px;">
        <el-button type="danger" plain :disabled="invalidCount === 0" @click="handleClearInvalid">
          <el-icon><Delete /></el-icon> {{ $adminText('清除失效（{count}）', { count: invalidCount }) }}
        </el-button>
        <el-button type="danger" :disabled="selectedRows.length === 0" @click="handleDeleteSelected">
          <el-icon><Delete /></el-icon> {{ $adminText('删除选中（{count}）', { count: selectedRows.length }) }}
        </el-button>
      </div>
    </div>

    <div class="admin-card">
      <!-- 筛选栏（服务端筛选） -->
      <div class="filter-bar">
        <el-input v-model="filterUser" :placeholder="$adminText('分享者模糊查询')" clearable style="width:200px" @change="reloadShares" />
        <el-select v-model="filterStatus" :placeholder="$adminText('全部状态')" clearable style="width:150px" @change="reloadShares">
          <el-option :label="$adminText('有效')" value="valid" />
          <el-option :label="$adminText('已过期')" value="expired" />
          <el-option :label="$adminText('次数用完')" value="exhausted" />
          <el-option :label="$adminText('会话已删')" value="orphaned" />
        </el-select>
        <el-button @click="filterUser = ''; filterStatus = ''; reloadShares()">{{ $adminText('重置') }}</el-button>
      </div>

      <!-- 分享表格：列宽按内容自适应（上限 50 汉字），selection 列支持批量操作 -->
      <el-table ref="tableRef" :data="shares" v-loading="loading" stripe border style="width:100%"
                @selection-change="onSelectionChange" row-key="id">
        <el-table-column type="selection" width="44" align="center" reserve-selection />
        <el-table-column prop="title" :label="$adminText('标题')" :min-width="colW.title" show-overflow-tooltip />
        <el-table-column prop="userName" :label="$adminText('分享者')" :width="colW.userName" show-overflow-tooltip />
        <el-table-column :label="$adminText('状态')" :width="colW.status" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.status === 'valid' ? 'status-enabled' : 'status-disabled'">
              {{ statusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="$adminText('创建时间')" :width="colW.createdAt" show-overflow-tooltip />
        <el-table-column :label="$adminText('有效期至')" :width="colW.expiresAt" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="row.expiresAt ? '' : 'color:var(--ink-3);'">{{ row.expiresAt || $adminText('永久') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('访问次数')" :width="colW.views" align="center" show-overflow-tooltip>
          <template #default="{ row }">{{ viewsText(row) }}</template>
        </el-table-column>
        <el-table-column :label="$adminText('访问密码')" :width="colW.password" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.passwordProtected">{{ row.password || $adminText('（旧数据不可查看）') }}</span>
            <span v-else style="color:var(--ink-3);">{{ $adminText('未设置') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('分享链接')" :min-width="colW.link" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-size:12px;font-family:var(--mono, monospace);">{{ shareUrl(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('操作')" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" text :title="$adminText('编辑安全设置')" @click="openShareEdit(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button size="small" text :title="$adminText('复制链接')" @click="copyLink(row)">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
              <el-button size="small" text :title="$adminText('新窗口打开')" @click="openLink(row)">
                <el-icon><View /></el-icon>
              </el-button>
              <el-button size="small" text type="danger" :title="$adminText('删除分享')" @click="handleDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      <!-- 统一分页组件（服务端分页：切换条数/翻页均回到加载逻辑） -->
      <AdminPager v-model:page="page" v-model:page-size="pageSize"
        :total="total" :total-pages="totalPages"
        @size-change="reloadShares" @page-change="loadShares" />
    </div>

    <ShareSettingsModal
      v-if="shareEditVisible"
      :share="shareEditTarget"
      :admin="true"
      @close="shareEditVisible = false"
      @saved="onShareEditSaved"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, CopyDocument, View, Edit } from '@element-plus/icons-vue'
import { getAdminShares, batchDeleteShares, deleteShare, deleteInvalidShares } from '@/api/share'
import { autoColWidth } from '@/composables/useTableAutoWidth'
import AdminPager from '@/components/admin/AdminPager.vue'
import ShareSettingsModal from '@/components/chat/ShareSettingsModal.vue'
import { adminApiText, adminText } from '@/i18n'

const loading = ref(false)
const shares = ref([])
const filterUser = ref('')
const filterStatus = ref('')
const tableRef = ref(null)
const selectedRows = ref([])

const page = ref(1)
const pageSize = ref(10)
const total = ref(0)
const totalPages = ref(1)
const invalidCount = ref(0)

const STATUS_TEXT = { valid: '有效', expired: '已过期', exhausted: '次数用完', orphaned: '会话已删' }
function statusText(s) {
  return adminText(STATUS_TEXT[s] || s)
}

// 列宽自适应：按当页列最长内容计算，上限 50 个汉字
const colW = computed(() => {
  const list = shares.value
  return {
    title: autoColWidth(list.map(s => s.title), { header: adminText('标题'), min: 160 }),
    userName: autoColWidth(list.map(s => s.userName), { header: adminText('分享者'), min: 90 }),
    status: autoColWidth(['已过期', '会话已删', '次数用完', '有效'].map(adminText), { header: adminText('状态'), extra: 24 }),
    createdAt: autoColWidth(list.map(s => s.createdAt), { header: adminText('创建时间') }),
    expiresAt: autoColWidth(list.map(s => s.expiresAt || adminText('永久')), { header: adminText('有效期至') }),
    views: autoColWidth(list.map(viewsText), { header: adminText('访问次数'), extra: 24 }),
    password: autoColWidth(list.map(s => s.passwordProtected ? (s.password || adminText('（旧数据不可查看）')) : adminText('未设置')), { header: adminText('访问密码') }),
    link: autoColWidth(list.map(shareUrl), { header: adminText('分享链接'), min: 200 })
  }
})

// 访问次数展示：已访问 / 上限（0 表示不限）
function viewsText(row) {
  return `${row.accessCount ?? 0} / ${row.maxViews > 0 ? row.maxViews : adminText('不限')}`
}

function shareUrl(row) {
  return location.origin + '/share/' + row.id
}

async function copyLink(row) {
  try {
    await navigator.clipboard.writeText(shareUrl(row))
    ElMessage.success(adminText('链接已复制'))
  } catch (e) {
    // 非 https 环境剪贴板可能不可用，降级弹窗展示
    ElMessageBox.alert(shareUrl(row), adminText('分享链接（请手动复制）'), { confirmButtonText: adminText('知道了') })
  }
}

function openLink(row) {
  window.open(shareUrl(row), '_blank')
}

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 打开分享安全设置编辑弹窗（管理员可修改任何人的分享）
const shareEditVisible = ref(false)
const shareEditTarget = ref(null)
function openShareEdit(row) {
  shareEditTarget.value = row
  shareEditVisible.value = true
}

// 编辑保存成功后刷新列表
async function onShareEditSaved() {
  await loadShares()
}

// 单条删除（撤销分享）
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(adminText('确定删除分享 "{title}" 吗？删除后该链接立即失效。', { title: row.title }), adminText('确认删除'), { type: 'warning' })
    const res = await deleteShare(row.id)
    if (res?.success) {
      ElMessage.success(adminText('已删除'))
      await loadShares()
    } else {
      ElMessage.error(adminApiText(res?.message, '删除失败'))
    }
  } catch { /* cancelled */ }
}

// 批量删除选中
async function handleDeleteSelected() {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(adminText('确定删除选中的 {count} 条分享吗？删除后链接立即失效。', { count: rows.length }), adminText('确认批量删除'), { type: 'warning' })
    await doBatchDelete(rows.map(r => r.id))
  } catch { /* cancelled */ }
}

// 一键清除失效（已过期 + 次数已用完 + 源会话已删），失效判定与删除均在服务端完成
async function handleClearInvalid() {
  if (invalidCount.value === 0) return
  try {
    await ElMessageBox.confirm(
      adminText('共 {count} 条失效分享（已过期、次数已用完或源会话已删除），确定全部清除吗？', { count: invalidCount.value }),
      adminText('确认清除失效'), { type: 'warning' }
    )
    const res = await deleteInvalidShares()
    if (res?.success) {
      ElMessage.success(adminText('已清除 {count} 条失效分享', { count: res.deleted ?? 0 }))
      tableRef.value?.clearSelection()
      await reloadShares()
    } else {
      ElMessage.error(adminApiText(res?.message, '清除失败'))
    }
  } catch { /* cancelled */ }
}

async function doBatchDelete(ids) {
  const res = await batchDeleteShares(ids)
  if (res?.success) {
    ElMessage.success(adminText('已删除 {count} 条分享', { count: res.deleted ?? ids.length }))
    tableRef.value?.clearSelection()
    await loadShares()
  } else {
    ElMessage.error(adminApiText(res?.message, '删除失败'))
  }
}

// 加载分享列表（服务端分页）；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadShares(silent = false) {
  if (!silent) loading.value = true
  try {
    const params = { page: page.value, size: pageSize.value }
    if (filterUser.value) params.username = filterUser.value
    if (filterStatus.value) params.status = filterStatus.value
    const res = await getAdminShares(params)
    if (res?.success) {
      shares.value = res.data || []
      total.value = res.total || 0
      totalPages.value = res.totalPages || 1
      page.value = res.page || 1
      invalidCount.value = res.invalidCount || 0
    }
  } finally {
    loading.value = false
  }
}

// 筛选/页大小变化：回到第一页重新加载
function reloadShares() {
  page.value = 1
  loadShares()
}

onMounted(loadShares)

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadShares(true)
})
</script>
