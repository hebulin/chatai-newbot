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

      <!-- 厂商表格：列宽按内容自适应（上限 50 汉字），border 模式支持拖拽表头调宽，超宽时横向滚动 -->
      <el-table :data="pagedProviders" v-loading="loading" stripe border style="width:100%">
        <el-table-column label="厂商" :width="colW.name">
          <template #default="{ row }">
            <div class="model-name-cell">
              <img v-if="providerIconMap[row.id]" :src="providerIconMap[row.id]" class="provider-icon" />
              <span v-else-if="row.icon" style="font-size:18px;">{{ row.icon }}</span>
              <span v-else style="width:20px;height:20px;display:inline-flex;align-items:center;justify-content:center;background:var(--paper-3);border-radius:4px;font-size:11px;color:var(--ink-3);">{{ row.id?.[0]?.toUpperCase() }}</span>
              <span class="model-name-text" :title="row.name">{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="类型" :width="colW.type" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.type === 'custom' ? 'vis-admin' : 'status-enabled'">
              {{ row.type === 'custom' ? '自定义' : '预置' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="厂商ID" :width="colW.id" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="model-id-text">{{ row.id }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="defaultApiUrl" label="API 地址" min-width="220" show-overflow-tooltip />
        <el-table-column label="模型数" :width="colW.count" align="center">
          <template #default="{ row }">{{ getModelCount(row) }}</template>
        </el-table-column>
        <el-table-column label="预设名称" :width="colW.defaultName" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="color:var(--ink-3)">{{ row.type === 'custom' ? '-' : (row.defaultName || row.name) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="显示名称" :width="colW.displayName" show-overflow-tooltip>
          <template #default="{ row }">
            <span style="font-weight:500;">{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="图标" :width="colW.icon" align="center">
          <template #default="{ row }">
            <span v-if="row.icon" style="font-size:18px;">{{ row.icon }}</span>
            <span v-else style="color:var(--ink-4)">未设置</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" align="center" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="showDetail(row)">明细</el-button>
            <el-button size="small" text aria-label="编辑厂商" @click="showRename(row)">
              <el-icon><Edit /></el-icon>
            </el-button>
            <el-button size="small" text aria-label="获取上游模型" @click="showCatalog(row)">获取模型</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- 统一分页组件（前端分页：切换条数重置页码，翻页由计算属性自动响应） -->
      <AdminPager v-model:page="providerPage" v-model:page-size="providerPageSize"
        :total="filteredProviders.length" :total-pages="providerTotalPages"
        @size-change="providerPage = 1" />
    </div>

    <!-- 厂商明细弹窗：模型数与列表页统一按厂商模型目录计算 -->
    <el-dialog v-model="detailVisible" :title="'厂商明细 - ' + detailProvider.name" width="760px" destroy-on-close>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="厂商 ID">{{ detailProvider.id || '-' }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ detailProvider.type === 'custom' ? '自定义' : '预置' }}</el-descriptions-item>
        <el-descriptions-item label="显示名称">{{ detailProvider.name || '-' }}</el-descriptions-item>
        <el-descriptions-item label="协议">{{ formatProtocol(detailProvider.protocol) }}</el-descriptions-item>
        <el-descriptions-item label="API 地址" :span="2">{{ detailProvider.defaultApiUrl || '-' }}</el-descriptions-item>
      </el-descriptions>
      <div class="detail-toolbar">
        <strong>支持模型（{{ getModelCount(detailProvider) }}）</strong>
        <el-input v-model="detailModelQuery" placeholder="按模型名称模糊查询" clearable style="width:240px" />
      </div>
      <el-table :data="filteredDetailModels" height="340" border empty-text="暂无匹配模型">
        <el-table-column prop="id" label="模型 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="name" label="模型名称" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.name || row.id }}</template>
        </el-table-column>
        <el-table-column label="思考" width="80" align="center">
          <template #default="{ row }">{{ row.supportsThinking ? '支持' : '不支持' }}</template>
        </el-table-column>
        <el-table-column label="多模态" width="90" align="center">
          <template #default="{ row }">{{ row.supportsMultimodal ? '支持' : '不支持' }}</template>
        </el-table-column>
      </el-table>
      <template #footer><el-button @click="detailVisible = false">关闭</el-button></template>
    </el-dialog>

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
        <el-form-item label="当前模型">
          <div class="provider-model-list">
            <el-tag v-for="model in renameForm.models" :key="model.id" size="small">{{ model.name || model.id }}</el-tag>
            <span v-if="!renameForm.models.length" style="color:var(--ink-4)">暂无已知模型</span>
          </div>
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

    <el-dialog v-model="catalogVisible" :title="'获取上游模型 - ' + catalogForm.name" width="680px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="API 地址">
          <el-input v-model="catalogForm.apiUrl" placeholder="例如 https://api.example.com/v1" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="catalogForm.apiKey" type="password" show-password placeholder="可留空，优先复用该厂商已接入模型的 Key" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="catalogLoading" @click="loadUpstreamModels">获取最新模型</el-button>
          <span v-if="catalogEndpoint" style="margin-left:10px;color:var(--ink-3);font-size:12px">{{ catalogEndpoint }}</span>
        </el-form-item>
      </el-form>
      <el-table ref="catalogTable" :data="fetchedModels" height="320" border @selection-change="handleCatalogSelection">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="id" label="模型 ID" min-width="220" show-overflow-tooltip />
        <el-table-column prop="name" label="模型名称" min-width="180" show-overflow-tooltip />
      </el-table>
      <template #footer>
        <el-button @click="catalogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedModels.length" :loading="catalogSaving" @click="saveCatalog">保存选中模型（{{ selectedModels.length }}）</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit } from '@element-plus/icons-vue'
import { getProviders, renameProvider, fetchProviderModels, saveProviderModels } from '@/api/providers'
import { autoColWidth } from '@/composables/useTableAutoWidth'
import AdminPager from '@/components/admin/AdminPager.vue'

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

// 列宽自适应：按当前列最长内容计算，上限 50 个汉字
const colW = computed(() => {
  const list = allProviders.value
  return {
    // 名称列：图标(20+间距8) extra 34
    name: autoColWidth(list.map(p => p.name), { header: '厂商', extra: 34, min: 110 }),
    type: autoColWidth(['自定义', '预置'], { header: '类型', extra: 24 }),
    // 厂商ID列以等宽字体渲染，按 mono 测宽
    id: autoColWidth(list.map(p => p.id), { header: '厂商ID', min: 100, mono: true }),
    apiUrl: autoColWidth(list.map(p => p.defaultApiUrl || ''), { header: 'API 地址', min: 160 }),
    count: autoColWidth(list.map(getModelCount), { header: '模型数' }),
    defaultName: autoColWidth(list.map(p => p.type === 'custom' ? '-' : (p.defaultName || p.name)), { header: '预设名称', min: 100 }),
    displayName: autoColWidth(list.map(p => p.name), { header: '显示名称', min: 100 }),
    icon: autoColWidth(list.map(p => p.icon || '未设置'), { header: '图标' })
  }
})

function resetFilter() {
  filterName.value = ''
  filterType.value = ''
  providerPage.value = 1
}

// 返回厂商目录中的模型总数，确保列表数字与厂商明细模型表一致
function getModelCount(p) {
  return Array.isArray(p?.models) ? p.models.length : (p?.modelCount || 0)
}

// ===== 厂商明细 =====
const detailVisible = ref(false)
const detailProvider = ref({ name: '', models: [] })
const detailModelQuery = ref('')

// 厂商明细模型名称采用大小写不敏感的包含匹配
const filteredDetailModels = computed(() => {
  const keyword = detailModelQuery.value.trim().toLocaleLowerCase()
  const models = Array.isArray(detailProvider.value.models) ? detailProvider.value.models : []
  if (!keyword) return models
  return models.filter(model => (model.name || model.id || '').toLocaleLowerCase().includes(keyword))
})

// 打开厂商明细并重置模型查询条件
function showDetail(row) {
  detailProvider.value = { ...row, models: Array.isArray(row.models) ? row.models : [] }
  detailModelQuery.value = ''
  detailVisible.value = true
}

// 将后端协议值转换为管理员可读文本
function formatProtocol(protocol) {
  if (protocol === 'anthropic') return 'Anthropic'
  if (protocol === 'openai') return 'OpenAI 兼容'
  return protocol || '-'
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
  oldName: '',
  models: []
})

function showRename(row) {
  renameForm.value = {
    providerId: row.id,
    isCustom: row.type === 'custom',
    currentName: row.name,
    currentIcon: row.icon || '',
    newName: row.name,
    newIcon: row.icon || '',
    oldName: row.name,
    models: Array.isArray(row.models) ? row.models : []
  }
  renameVisible.value = true
}

// ===== 上游模型目录 =====
const catalogVisible = ref(false)
const catalogLoading = ref(false)
const catalogSaving = ref(false)
const catalogTable = ref(null)
const catalogForm = ref({ providerId: '', name: '', apiUrl: '', apiKey: '' })
const fetchedModels = ref([])
const selectedModels = ref([])

// 保存上游模型表格的当前多选结果
function handleCatalogSelection(rows) {
  selectedModels.value = rows
}
const catalogEndpoint = ref('')

// 打开上游模型目录弹窗并保留当前厂商地址
function showCatalog(row) {
  catalogForm.value = { providerId: row.id, name: row.name, apiUrl: row.defaultApiUrl || '', apiKey: '' }
  fetchedModels.value = []
  selectedModels.value = []
  catalogEndpoint.value = ''
  catalogVisible.value = true
}

// 获取上游模型并默认全选，管理员可取消不希望入库的条目
async function loadUpstreamModels() {
  if (!catalogForm.value.apiUrl.trim()) { ElMessage.warning('请输入 API 地址'); return }
  catalogLoading.value = true
  try {
    const res = await fetchProviderModels(catalogForm.value.providerId, {
      apiUrl: catalogForm.value.apiUrl.trim(), apiKey: catalogForm.value.apiKey.trim()
    })
    if (!res?.success) { ElMessage.error(res?.message || '获取失败'); return }
    fetchedModels.value = res.data || []
    catalogEndpoint.value = res.endpoint || ''
    await nextTick()
    fetchedModels.value.forEach(row => catalogTable.value?.toggleRowSelection(row, true))
    ElMessage.success(`已获取 ${fetchedModels.value.length} 个模型`)
  } finally {
    catalogLoading.value = false
  }
}

// 保存管理员勾选的模型目录
async function saveCatalog() {
  catalogSaving.value = true
  try {
    const res = await saveProviderModels(catalogForm.value.providerId, selectedModels.value)
    if (res?.success) {
      ElMessage.success(res.message || '模型目录已保存')
      catalogVisible.value = false
      await loadData()
    } else ElMessage.error(res?.message || '保存失败')
  } finally {
    catalogSaving.value = false
  }
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

// 加载厂商与模型数据；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadData(silent = false) {
  if (!silent) loading.value = true
  try {
    const pRes = await getProviders()
    if (pRes?.success) allProviders.value = pRes.data || []
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

// keep-alive 缓存下再次进入本页时静默刷新数据；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadData(true)
})
</script>

<style scoped>
.provider-model-list {
  display: flex;
  max-height: 120px;
  flex-wrap: wrap;
  gap: 6px;
  overflow-y: auto;
}
.detail-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin: 18px 0 10px;
}
@media (max-width: 720px) {
  .detail-toolbar { align-items: stretch; flex-direction: column; }
  .detail-toolbar :deep(.el-input) { width: 100% !important; }
}
</style>
