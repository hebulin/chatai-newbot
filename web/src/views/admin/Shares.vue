<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">05 / SHARES · 分享</span>
        <h2>分享管理</h2>
      </div>
      <div style="display:flex;gap:8px;">
        <el-button type="danger" plain :disabled="invalidShares.length === 0" @click="handleClearInvalid">
          <el-icon><Delete /></el-icon> 清除失效（{{ invalidShares.length }}）
        </el-button>
        <el-button type="danger" :disabled="selectedRows.length === 0" @click="handleDeleteSelected">
          <el-icon><Delete /></el-icon> 删除选中（{{ selectedRows.length }}）
        </el-button>
      </div>
    </div>

    <div class="admin-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-input v-model="filterUser" placeholder="分享者模糊查询" clearable style="width:200px" @input="page = 1" />
        <el-select v-model="filterStatus" placeholder="全部状态" clearable style="width:150px" @change="page = 1">
          <el-option label="有效" value="valid" />
          <el-option label="已过期" value="expired" />
          <el-option label="会话已删" value="orphaned" />
        </el-select>
        <el-button @click="filterUser = ''; filterStatus = ''; page = 1">重置</el-button>
      </div>

      <!-- 分享表格：列宽按内容自适应（上限 50 汉字），selection 列支持批量操作 -->
      <el-table ref="tableRef" :data="pagedShares" v-loading="loading" stripe border style="width:100%"
                @selection-change="onSelectionChange" row-key="id">
        <el-table-column type="selection" width="44" align="center" reserve-selection />
        <el-table-column prop="title" label="标题" :min-width="colW.title" show-overflow-tooltip />
        <el-table-column prop="userName" label="分享者" :width="colW.userName" show-overflow-tooltip />
        <el-table-column label="状态" :width="colW.status" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.status === 'valid' ? 'status-enabled' : 'status-disabled'">
              {{ statusText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" :width="colW.createdAt" show-overflow-tooltip />
        <el-table-column label="有效期至" :width="colW.expiresAt" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="row.expiresAt ? '' : 'color:var(--ink-3);'">{{ row.expiresAt || '永久' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="分享链接" :min-width="colW.link" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-size:12px;font-family:var(--mono, monospace);">{{ shareUrl(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" align="center" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" text title="复制链接" @click="copyLink(row)">
                <el-icon><CopyDocument /></el-icon>
              </el-button>
              <el-button size="small" text title="新窗口打开" @click="openLink(row)">
                <el-icon><View /></el-icon>
              </el-button>
              <el-button size="small" text type="danger" title="删除分享" @click="handleDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      <div class="admin-pager">
        <select v-model.number="pageSize" class="admin-pager-size" @change="page = 1">
          <option :value="10">10条/页</option>
          <option :value="20">20条/页</option>
          <option :value="50">50条/页</option>
        </select>
        <button class="admin-pager-btn" :disabled="page <= 1" @click="page--">上一页</button>
        <span class="admin-pager-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ filteredShares.length }} 条</span>
        <button class="admin-pager-btn" :disabled="page >= totalPages" @click="page++">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, CopyDocument, View } from '@element-plus/icons-vue'
import { getAdminShares, batchDeleteShares, deleteShare } from '@/api/share'
import { autoColWidth } from '@/composables/useTableAutoWidth'

const loading = ref(false)
const allShares = ref([])
const filterUser = ref('')
const filterStatus = ref('')
const tableRef = ref(null)
const selectedRows = ref([])

const page = ref(1)
const pageSize = ref(10)

const STATUS_TEXT = { valid: '有效', expired: '已过期', orphaned: '会话已删' }
function statusText(s) {
  return STATUS_TEXT[s] || s
}

// 失效分享 = 已过期 + 源会话已删
const invalidShares = computed(() => allShares.value.filter(s => s.status !== 'valid'))

const filteredShares = computed(() => {
  let list = allShares.value
  if (filterUser.value) {
    const kw = filterUser.value.toLowerCase()
    list = list.filter(s => (s.userName || '').toLowerCase().includes(kw))
  }
  if (filterStatus.value) {
    list = list.filter(s => s.status === filterStatus.value)
  }
  return list
})

const pagedShares = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return filteredShares.value.slice(start, start + pageSize.value)
})
const totalPages = computed(() => Math.max(1, Math.ceil(filteredShares.value.length / pageSize.value)))

// 列宽自适应：按当前列最长内容计算，上限 50 个汉字
const colW = computed(() => {
  const list = allShares.value
  return {
    title: autoColWidth(list.map(s => s.title), { header: '标题', min: 160 }),
    userName: autoColWidth(list.map(s => s.userName), { header: '分享者', min: 90 }),
    status: autoColWidth(['已过期', '会话已删', '有效'], { header: '状态', extra: 24 }),
    createdAt: autoColWidth(list.map(s => s.createdAt), { header: '创建时间' }),
    expiresAt: autoColWidth(list.map(s => s.expiresAt || '永久'), { header: '有效期至' }),
    link: autoColWidth(list.map(shareUrl), { header: '分享链接', min: 200 })
  }
})

function shareUrl(row) {
  return location.origin + '/share/' + row.id
}

async function copyLink(row) {
  try {
    await navigator.clipboard.writeText(shareUrl(row))
    ElMessage.success('链接已复制')
  } catch (e) {
    // 非 https 环境剪贴板可能不可用，降级弹窗展示
    ElMessageBox.alert(shareUrl(row), '分享链接（请手动复制）', { confirmButtonText: '知道了' })
  }
}

function openLink(row) {
  window.open(shareUrl(row), '_blank')
}

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 单条删除（撤销分享）
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除分享 "${row.title}" 吗？删除后该链接立即失效。`, '确认删除', { type: 'warning' })
    const res = await deleteShare(row.id)
    if (res?.success) {
      ElMessage.success('已删除')
      await loadShares()
    } else {
      ElMessage.error(res?.message || '删除失败')
    }
  } catch { /* cancelled */ }
}

// 批量删除选中
async function handleDeleteSelected() {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${rows.length} 条分享吗？删除后链接立即失效。`, '确认批量删除', { type: 'warning' })
    await doBatchDelete(rows.map(r => r.id))
  } catch { /* cancelled */ }
}

// 一键清除失效（已过期 + 源会话已删）
async function handleClearInvalid() {
  const rows = invalidShares.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(
      `共 ${rows.length} 条失效分享（已过期或源会话已删除），确定全部清除吗？`,
      '确认清除失效', { type: 'warning' }
    )
    await doBatchDelete(rows.map(r => r.id))
  } catch { /* cancelled */ }
}

async function doBatchDelete(ids) {
  const res = await batchDeleteShares(ids)
  if (res?.success) {
    ElMessage.success(`已删除 ${res.deleted ?? ids.length} 条分享`)
    tableRef.value?.clearSelection()
    await loadShares()
  } else {
    ElMessage.error(res?.message || '删除失败')
  }
}

async function loadShares() {
  loading.value = true
  try {
    const res = await getAdminShares()
    if (res?.success) allShares.value = res.data || []
  } finally {
    loading.value = false
  }
}

onMounted(loadShares)
</script>
