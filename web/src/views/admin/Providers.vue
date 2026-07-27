<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">03 / PROVIDERS · 厂商</span>
        <h2>厂商管理</h2>
      </div>
      <span class="section-tip">预置厂商仅可修改显示名，ID/协议/默认URL不可改；自定义厂商可修改显示名（同步至所有关联模型）</span>
    </div>

    <div class="admin-card">
      <!-- 筛选栏 -->
      <div class="filter-bar">
        <el-input v-model="filterName" placeholder="名称模糊查询" clearable style="width:200px" @input="providerPage = 1" />
        <el-select v-model="filterType" placeholder="类型" clearable style="width:120px" @change="providerPage = 1">
          <el-option label="预置" value="preset" />
          <el-option label="自定义" value="custom" />
        </el-select>
        <el-button @click="resetFilter">重置</el-button>
      </div>

      <!-- 厂商表格 -->
      <el-table :data="pagedProviders" v-loading="loading" stripe style="width:100%">
        <el-table-column label="厂商" min-width="160">
          <template #default="{ row }">
            <div class="model-name-cell">
              <img v-if="providerIconMap[row.id]" :src="providerIconMap[row.id]" class="provider-icon" />
              <span v-else-if="row.icon" style="font-size:18px;">{{ row.icon }}</span>
              <span v-else style="width:20px;height:20px;display:inline-flex;align-items:center;justify-content:center;background:var(--paper-3);border-radius:4px;font-size:11px;color:var(--ink-3);">{{ row.id?.[0]?.toUpperCase() }}</span>
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.type === 'custom' ? 'vis-admin' : 'status-enabled'">
              {{ row.type === 'custom' ? '自定义' : '预置' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="厂商ID" width="140">
          <template #default="{ row }">
            <span class="model-id-text">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column label="模型数" width="80" align="center">
          <template #default="{ row }">{{ getModelCount(row) }}</template>
        </el-table-column>
        <el-table-column label="预设名称" width="120">
          <template #default="{ row }">
            <span style="color:var(--ink-3)">{{ row.type === 'custom' ? '-' : (row.defaultName || row.name) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="显示名称" width="120">
          <template #default="{ row }">
            <span style="font-weight:500;">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="图标" width="70" align="center">
          <template #default="{ row }">
            <span v-if="row.icon" style="font-size:18px;">{{ row.icon }}</span>
            <span v-else style="color:var(--ink-4)">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="showRename(row)">
              <el-icon><Edit /></el-icon>
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="admin-pager">
        <select v-model.number="providerPageSize" class="admin-pager-size" @change="providerPage = 1">
          <option :value="10">10条/页</option>
          <option :value="20">20条/页</option>
          <option :value="50">50条/页</option>
        </select>
        <button class="admin-pager-btn" :disabled="providerPage <= 1" @click="providerPage--">上一页</button>
        <span class="admin-pager-info">第 {{ providerPage }} / {{ providerTotalPages }} 页 · 共 {{ filteredProviders.length }} 条</span>
        <button class="admin-pager-btn" :disabled="providerPage >= providerTotalPages" @click="providerPage++">下一页</button>
      </div>
    </div>

    <!-- 修改厂商弹窗 -->
    <el-dialog v-model="renameVisible" :title="renameForm.isCustom ? '修改自定义厂商' : '修改预置厂商'" width="520px" destroy-on-close>
      <el-form label-width="120px">
        <el-form-item label="厂商ID">
          <el-input :model-value="renameForm.providerId" disabled />
        </el-form-item>
        <el-form-item v-if="!renameForm.isCustom" label="预设名称">
          <span style="color:var(--ink-3)">{{ renameForm.currentName }}</span>
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="renameForm.newName" maxlength="100" placeholder="请输入新的显示名" />
        </el-form-item>
        <el-form-item label="模型图标">
          <template v-if="renameForm.isCustom">
            <div class="icon-picker">
              <span
                v-for="ic in PRESET_ICONS"
                :key="ic"
                class="icon-picker-item"
                :class="{ selected: renameForm.newIcon === ic }"
                @click="renameForm.newIcon = ic"
              >{{ ic }}</span>
              <el-input v-model="renameForm.newIcon" maxlength="4" placeholder="自定义 emoji" style="width:120px;margin-left:8px;" />
            </div>
          </template>
          <template v-else>
            <span v-if="renameForm.currentIcon" style="font-size:22px;">{{ renameForm.currentIcon }}</span>
            <span v-else style="color:var(--ink-4)">未设置</span>
            <span style="font-size:12px;color:var(--ink-4);margin-left:8px;">预置厂商的图标不可修改</span>
          </template>
        </el-form-item>
        <div style="font-size:12px;color:var(--ink-3);margin-top:4px;">
          {{ renameForm.isCustom ? '修改后将同步更新所有该自定义厂商下的模型（仅匹配当前原名）' : '修改后预置厂商的显示名将立即更新，并同步至所有关联模型（ID/协议/默认URL等不可改）' }}
        </div>
      </el-form>
      <template #footer>
        <el-button @click="renameVisible = false">取消</el-button>
        <el-button type="primary" @click="submitRename" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit } from '@element-plus/icons-vue'
import { getProviders, renameProvider } from '@/api/providers'
import { getModels } from '@/api/models'

const PRESET_ICONS = ['🔮','🟣','🌙','🟢','⚡','🫘','⭐','🚀','🤖','💎','🎨','🛠️']

const providerIconMap = {
  deepseek: '/icons/deepseek-icon.svg',
  qwen: '/icons/qwen-icon.svg',
  kimi: '/icons/kimi-icon.svg',
  zhipu: '/icons/zhipu-icon.svg',
  minimax: '/icons/minimax-icon.svg',
  doubao: '/icons/doubao-icon.svg'
}

const loading = ref(false)
const submitting = ref(false)
const allProviders = ref([])
const allModels = ref([])

const filterName = ref('')
const filterType = ref('')

const providerPage = ref(1)
const providerPageSize = ref(10)

const filteredProviders = computed(() => {
  return allProviders.value.filter(p => {
    if (filterType.value && p.type !== filterType.value) return false
    if (filterName.value) {
      const hay = ((p.name || '') + ' ' + (p.id || '')).toLowerCase()
      if (!hay.includes(filterName.value.toLowerCase())) return false
    }
    return true
  })
})

const pagedProviders = computed(() => {
  const start = (providerPage.value - 1) * providerPageSize.value
  return filteredProviders.value.slice(start, start + providerPageSize.value)
})
const providerTotalPages = computed(() => Math.max(1, Math.ceil(filteredProviders.value.length / providerPageSize.value)))

function resetFilter() {
  filterName.value = ''
  filterType.value = ''
  providerPage.value = 1
}

function getModelCount(p) {
  if (p.type === 'custom') {
    return allModels.value.filter(m => m.providerId === '__custom__' && m.providerName === p.name).length
  }
  return allModels.value.filter(m => m.providerId === p.id).length
}

// 修改厂商
const renameVisible = ref(false)
const renameForm = ref({
  providerId: '',
  isCustom: false,
  currentName: '',
  currentIcon: '',
  newName: '',
  newIcon: '',
  oldName: ''
})

function showRename(row) {
  renameForm.value = {
    providerId: row.id,
    isCustom: row.type === 'custom',
    currentName: row.name,
    currentIcon: row.icon || '',
    newName: row.name,
    newIcon: row.icon || '',
    oldName: row.name
  }
  renameVisible.value = true
}

async function submitRename() {
  if (!renameForm.value.newName.trim()) {
    ElMessage.warning('请输入新的显示名')
    return
  }
  submitting.value = true
  try {
    const payload = { name: renameForm.value.newName.trim() }
    if (renameForm.value.isCustom) {
      payload.icon = renameForm.value.newIcon.trim()
      payload.oldName = renameForm.value.oldName
    }
    const res = await renameProvider(renameForm.value.providerId, payload)
    if (res?.success) {
      ElMessage.success(res.message || '已更新')
      renameVisible.value = false
      await loadData()
    } else {
      ElMessage.error(res?.message || '更新失败')
    }
  } finally {
    submitting.value = false
  }
}

async function loadData() {
  loading.value = true
  try {
    const [pRes, mRes] = await Promise.all([getProviders(), getModels()])
    if (pRes?.success) allProviders.value = pRes.data || []
    if (mRes?.success) allModels.value = mRes.data || []
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>
