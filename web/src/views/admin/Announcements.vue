<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">08 / ANNOUNCEMENTS · {{ $adminText('公告') }}</span>
        <h2>{{ $adminText('系统公告管理') }}</h2>
      </div>
    </div>

    <!-- 发布新公告 -->
    <div class="admin-card">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">{{ $adminText('发布新公告') }}</h3>

      <el-input
        v-model="newTitle"
        maxlength="100"
        show-word-limit
        :placeholder="$adminText('公告标题（用户端弹窗标题展示）')"
        style="width:420px;margin-bottom:12px;display:block;"
      />

      <el-input
        v-model="newContent"
        type="textarea"
        :rows="5"
        maxlength="5000"
        show-word-limit
        :placeholder="$adminText('输入公告内容，登录用户进入聊天页时会弹窗展示')"
        style="margin-bottom:12px;"
      />

      <div style="display:flex;align-items:center;gap:12px;margin-bottom:16px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);">{{ $adminText('公告期') }}</span>
        <el-date-picker
          v-model="newStartAt"
          type="datetime"
          :placeholder="$adminText('开始时间（留空=立即生效）')"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width:220px;"
        />
        <span style="font-size:13px;color:var(--ink-3);">{{ $adminText('至') }}</span>
        <el-date-picker
          v-model="newEndAt"
          type="datetime"
          :placeholder="$adminText('结束时间（留空=长期有效）')"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width:220px;"
        />
        <el-button type="primary" @click="handlePublish" :loading="publishing">{{ $adminText('发布公告') }}</el-button>
      </div>

      <div style="font-size:13px;color:var(--ink-3);line-height:1.8;background:var(--paper-2);padding:12px 16px;border-radius:8px;border:1px solid var(--line);">
        {{ $adminText('公告仅在公告期内对用户弹窗展示，公告期留空表示立即生效、长期有效。发布新公告会自动下线当前生效的公告（同一时刻最多一条生效）。重新发布或重新生效（更新时间变化）后，勾选过“不再提示”的用户也会再次收到弹窗提醒。') }}
      </div>
    </div>

    <!-- 历史公告 -->
    <div class="admin-card" style="margin-top:20px;">
      <h3 style="font-size:16px;font-weight:600;margin-bottom:16px;color:var(--ink);">{{ $adminText('历史公告') }}</h3>

      <el-table :data="pagedAnnouncements" v-loading="loading" stripe border style="width:100%" row-key="id">
        <el-table-column prop="title" :label="$adminText('标题')" :width="colW.title" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="row.title ? '' : 'color:var(--ink-3);'">{{ row.title || $adminText('系统公告') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="content" :label="$adminText('公告内容')" min-width="240" show-overflow-tooltip />
        <el-table-column :label="$adminText('公告期')" :width="colW.period" show-overflow-tooltip>
          <template #default="{ row }">
            <span :style="row.startAt || row.endAt ? '' : 'color:var(--ink-3);'">{{ periodText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="$adminText('状态')" :width="colW.status" align="center">
          <template #default="{ row }">
            <el-tag :type="STATUS_TAG[row.status] || 'info'" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" :label="$adminText('最后生效时间')" :width="colW.updatedAt" show-overflow-tooltip />
        <el-table-column prop="createdAt" :label="$adminText('创建时间')" :width="colW.createdAt" show-overflow-tooltip />
        <!-- 列宽需容纳最长组合「重新生效+下线+删除」，fixed 列会裁切溢出内容导致按钮文字不显示 -->
        <el-table-column :label="$adminText('操作')" width="230" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openRepublish(row)">
              {{ $adminText(row.status === 'active' ? '改期' : '重新生效') }}
            </el-button>
            <el-button size="small" text v-if="row.enabled" @click="handleOffline(row)">{{ $adminText('下线') }}</el-button>
            <el-button size="small" text type="danger" @click="handleDelete(row)">{{ $adminText('删除') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 统一分页组件（前端分页：切换条数重置页码，翻页由计算属性自动响应） -->
      <AdminPager v-model:page="page" v-model:page-size="pageSize"
        :total="announcements.length" :total-pages="totalPages"
        @size-change="page = 1" />
    </div>

    <!-- 重新生效 / 更改公告期 -->
    <el-dialog v-model="dlgVisible" :title="dlgTitle" width="560px">
      <el-input
        v-model="dlgAnnTitle"
        maxlength="100"
        show-word-limit
        :placeholder="$adminText('公告标题')"
        style="margin-bottom:12px;"
      />
      <el-input
        v-model="dlgContent"
        type="textarea"
        :rows="5"
        maxlength="5000"
        show-word-limit
        :placeholder="$adminText('公告内容')"
        style="margin-bottom:16px;"
      />
      <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap;">
        <span style="font-size:13px;color:var(--ink-3);">{{ $adminText('公告期') }}</span>
        <el-date-picker
          v-model="dlgStartAt"
          type="datetime"
          :placeholder="$adminText('开始时间（留空=立即生效）')"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width:210px;"
        />
        <span style="font-size:13px;color:var(--ink-3);">{{ $adminText('至') }}</span>
        <el-date-picker
          v-model="dlgEndAt"
          type="datetime"
          :placeholder="$adminText('结束时间（留空=长期有效）')"
          value-format="YYYY-MM-DD HH:mm:ss"
          style="width:210px;"
        />
      </div>
      <div style="font-size:12px;color:var(--ink-3);margin-top:12px;line-height:1.7;">
        {{ $adminText('确认后该公告将按新公告期重新生效，其它公告自动下线；已读过的用户会再次收到弹窗提醒。') }}
      </div>
      <template #footer>
        <el-button @click="dlgVisible = false">{{ $adminText('取消') }}</el-button>
        <el-button type="primary" @click="handleRepublish" :loading="republishing">{{ $adminText('确认生效') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAnnouncements, publishAnnouncement, republishAnnouncement, offlineAnnouncement, deleteAnnouncement } from '@/api/settings'
import { autoColWidth } from '@/composables/useTableAutoWidth'
import AdminPager from '@/components/admin/AdminPager.vue'
import { adminApiText, adminText } from '@/i18n'

const loading = ref(false)
const announcements = ref([])

const page = ref(1)
const pageSize = ref(10)

const STATUS_TEXT = { active: '生效中', scheduled: '待生效', expired: '已过期', offline: '已下线' }
const STATUS_TAG = { active: 'success', scheduled: 'warning', expired: 'info', offline: 'info' }

// 将公告状态编码转换为当前语言文案。
function statusText(status) {
  return adminText(STATUS_TEXT[status] || status)
}

const pagedAnnouncements = computed(() => {
  const start = (page.value - 1) * pageSize.value
  return announcements.value.slice(start, start + pageSize.value)
})
const totalPages = computed(() => Math.max(1, Math.ceil(announcements.value.length / pageSize.value)))

// 列宽自适应：按当前列最长内容计算
const colW = computed(() => {
  const list = announcements.value
  return {
    title: autoColWidth(list.map(a => a.title || adminText('系统公告')), { header: adminText('标题'), min: 100 }),
    period: autoColWidth(list.map(periodText), { header: adminText('公告期'), min: 140 }),
    status: autoColWidth(Object.values(STATUS_TEXT).map(adminText), { header: adminText('状态'), extra: 24 }),
    updatedAt: autoColWidth(list.map(a => a.updatedAt), { header: adminText('最后生效时间') }),
    createdAt: autoColWidth(list.map(a => a.createdAt), { header: adminText('创建时间') })
  }
})

// 公告期展示：两端都未设置显示"长期有效"
function periodText(row) {
  if (!row.startAt && !row.endAt) return adminText('长期有效')
  return `${row.startAt || adminText('立即')} ~ ${row.endAt || adminText('长期')}`
}

// 加载公告列表；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadAnnouncements(silent = false) {
  if (!silent) loading.value = true
  try {
    const res = await listAnnouncements()
    if (res?.success) announcements.value = res.data || []
  } finally {
    loading.value = false
  }
}

// ===== 发布新公告 =====
const newTitle = ref('')
const newContent = ref('')
const newStartAt = ref('')
const newEndAt = ref('')
const publishing = ref(false)

// 前端预校验公告期（后端同样校验）
function validatePeriod(startAt, endAt) {
  if (startAt && endAt && startAt >= endAt) {
    ElMessage.warning(adminText('公告期开始时间必须早于结束时间'))
    return false
  }
  return true
}

async function handlePublish() {
  if (!newTitle.value.trim()) {
    ElMessage.warning(adminText('公告标题不能为空'))
    return
  }
  if (!newContent.value.trim()) {
    ElMessage.warning(adminText('公告内容不能为空'))
    return
  }
  if (!validatePeriod(newStartAt.value, newEndAt.value)) return
  publishing.value = true
  try {
    const res = await publishAnnouncement({
      title: newTitle.value.trim(),
      content: newContent.value.trim(),
      startAt: newStartAt.value || '',
      endAt: newEndAt.value || ''
    })
    if (res?.success) {
      ElMessage.success(adminApiText(res.message, '公告已发布'))
      newTitle.value = ''
      newContent.value = ''
      newStartAt.value = ''
      newEndAt.value = ''
      await loadAnnouncements()
    } else {
      ElMessage.error(adminApiText(res?.message, '发布失败'))
    }
  } finally {
    publishing.value = false
  }
}

// ===== 重新生效 / 更改公告期 =====
const dlgVisible = ref(false)
const dlgTitle = ref('')
const dlgId = ref('')
const dlgAnnTitle = ref('')
const dlgContent = ref('')
const dlgStartAt = ref('')
const dlgEndAt = ref('')
const republishing = ref(false)

function openRepublish(row) {
  dlgId.value = row.id
  dlgTitle.value = adminText(row.status === 'active' ? '更改公告期' : '重新生效')
  dlgAnnTitle.value = row.title || ''
  dlgContent.value = row.content || ''
  dlgStartAt.value = row.startAt || ''
  dlgEndAt.value = row.endAt || ''
  dlgVisible.value = true
}

async function handleRepublish() {
  if (!dlgAnnTitle.value.trim()) {
    ElMessage.warning(adminText('公告标题不能为空'))
    return
  }
  if (!dlgContent.value.trim()) {
    ElMessage.warning(adminText('公告内容不能为空'))
    return
  }
  if (!validatePeriod(dlgStartAt.value, dlgEndAt.value)) return
  republishing.value = true
  try {
    const res = await republishAnnouncement(dlgId.value, {
      title: dlgAnnTitle.value.trim(),
      content: dlgContent.value.trim(),
      startAt: dlgStartAt.value || '',
      endAt: dlgEndAt.value || ''
    })
    if (res?.success) {
      ElMessage.success(adminApiText(res.message, '公告已重新生效'))
      dlgVisible.value = false
      await loadAnnouncements()
    } else {
      ElMessage.error(adminApiText(res?.message, '操作失败'))
    }
  } finally {
    republishing.value = false
  }
}

// ===== 下线 / 删除 =====
async function handleOffline(row) {
  try {
    await ElMessageBox.confirm(adminText('确定下线该公告吗？下线后用户将不再看到公告弹窗，记录保留在历史公告中，可随时重新生效。'), adminText('下线公告'), { type: 'warning' })
  } catch { return }
  const res = await offlineAnnouncement(row.id)
  if (res?.success) {
    ElMessage.success(adminApiText(res.message, '公告已下线'))
    await loadAnnouncements()
  } else {
    ElMessage.error(adminApiText(res?.message, '下线失败'))
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(adminText('确定删除该公告记录吗？删除后不可恢复。'), adminText('删除公告'), { type: 'warning' })
  } catch { return }
  const res = await deleteAnnouncement(row.id)
  if (res?.success) {
    ElMessage.success(adminApiText(res.message, '公告已删除'))
    await loadAnnouncements()
  } else {
    ElMessage.error(adminApiText(res?.message, '删除失败'))
  }
}

onMounted(loadAnnouncements)

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadAnnouncements(true)
})
</script>
