<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">02 / MODELS · 模型</span>
        <h2>模型管理</h2>
      </div>
      <div style="display:flex;gap:8px;">
        <el-button type="danger" :disabled="selectedRows.length === 0" @click="handleDeleteSelected">
          <el-icon><Delete /></el-icon> 删除选中（{{ selectedRows.length }}）
        </el-button>
        <el-button type="primary" @click="showAddModel">
          <el-icon><Plus /></el-icon> 添加模型
        </el-button>
      </div>
    </div>

    <div class="admin-card">
      <!-- 筛选栏：手机端默认只展示前两个条件，filter-extra 隐藏可展开 -->
      <div class="filter-bar" :class="{ 'filter-collapsed': !filterExpanded }">
        <el-input v-model="filters.name" placeholder="名称模糊查询" clearable style="width:180px" @input="applyFilter" />
        <el-select v-model="filters.providerId" placeholder="全部厂商" clearable style="width:150px" @change="applyFilter">
          <el-option v-for="opt in providerFilterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select v-model="filters.modelId" class="filter-extra" placeholder="全部模型" clearable filterable style="width:180px" @change="applyFilter">
          <el-option v-for="opt in modelIdFilterOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
        </el-select>
        <el-select v-model="filters.thinking" class="filter-extra" placeholder="思考" clearable style="width:110px" @change="applyFilter">
          <el-option label="支持" value="1" />
          <el-option label="不支持" value="0" />
        </el-select>
        <el-select v-model="filters.multimodal" class="filter-extra" placeholder="多模态" clearable style="width:110px" @change="applyFilter">
          <el-option label="支持" value="1" />
          <el-option label="不支持" value="0" />
        </el-select>
        <el-select v-model="filters.enabled" class="filter-extra" placeholder="状态" clearable style="width:110px" @change="applyFilter">
          <el-option label="已启用" value="1" />
          <el-option label="已禁用" value="0" />
        </el-select>
        <el-select v-model="filters.visible" class="filter-extra" placeholder="可见性" clearable style="width:120px" @change="applyFilter">
          <el-option label="所有人" value="1" />
          <el-option label="仅管理员" value="0" />
        </el-select>
        <el-button @click="resetFilter">重置</el-button>
        <el-button class="filter-toggle-btn" @click="filterExpanded = !filterExpanded">
          {{ filterExpanded ? '收起' : '更多筛选' }}
          <el-icon style="margin-left:4px;"><ArrowUp v-if="filterExpanded" /><ArrowDown v-else /></el-icon>
        </el-button>
      </div>

      <!-- 模型表格：列宽按内容自适应（上限 50 汉字），border 模式支持拖拽表头调宽，超宽时横向滚动；selection 列支持跨页勾选批量删除 -->
      <el-table ref="tableRef" :data="pagedModels" v-loading="loading" stripe border style="width:100%"
                @selection-change="onSelectionChange" row-key="id">
        <el-table-column type="selection" width="44" align="center" reserve-selection />
        <el-table-column label="名称" :width="colW.name">
          <template #default="{ row }">
            <div class="model-name-cell">
              <img v-if="getModelIcon(row)" :src="getModelIcon(row)" class="provider-icon" />
              <span v-else-if="row.providerIcon" style="font-size:16px;">{{ row.providerIcon }}</span>
              <span class="model-name-text" :title="row.displayName || row.modelId">{{ row.displayName || row.modelId }}</span>
              <span v-if="row.id === defaultModelId" class="default-badge">默认</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="providerName" label="厂商" :width="colW.provider" show-overflow-tooltip />
        <el-table-column prop="apiUrl" label="API 地址" min-width="220" show-overflow-tooltip />
        <el-table-column label="模型" :width="colW.modelId" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="model-id-text">{{ row.modelId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="思考" :width="colW.thinking" align="center">
          <template #default="{ row }">
            <span v-if="row.supportsThinking" class="think-badge">支持</span>
            <span v-else style="color:var(--ink-4)">不支持</span>
          </template>
        </el-table-column>
        <el-table-column label="多模态" :width="colW.multimodal" align="center">
          <template #default="{ row }">
            <span v-if="row.supportsMultimodal" class="mm-badge">支持</span>
            <span v-else style="color:var(--ink-4)">不支持</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" :width="colW.status" align="center">
          <template #default="{ row }">
            <span
              class="status-badge"
              :class="row.enabled ? 'status-enabled' : 'status-disabled'"
              @click="toggleModel(row)"
              style="cursor:pointer"
            >{{ row.enabled ? '已启用' : '已禁用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="可见性" :width="colW.visible" align="center">
          <template #default="{ row }">
            <span class="status-badge" :class="row.visibleToAll ? 'vis-all' : 'vis-admin'">
              {{ row.visibleToAll ? '所有人' : '仅管理员' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="延迟" :width="colW.latency" align="center">
          <template #default="{ row }">
            <span v-if="row.testLatencyMs != null" class="metric-text" :class="latencyClass(row.testLatencyMs)" :title="row.testedAt ? '测试于 ' + row.testedAt : ''">{{ row.testLatencyMs }} ms</span>
            <span v-else class="metric-empty" title="点击右侧测试连接后更新">-</span>
          </template>
        </el-table-column>
        <el-table-column label="速度" :width="colW.speed" align="center">
          <template #default="{ row }">
            <span v-if="row.testSpeed != null" class="metric-text" :title="row.testedAt ? '测试于 ' + row.testedAt : ''">{{ row.testSpeed }} tk/s</span>
            <span v-else class="metric-empty" title="点击右侧测试连接后更新">-</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" align="center" fixed="right">
          <template #default="{ row }">
            <el-button-group>
              <el-button size="small" text @click="handleDefault(row)">
                <el-icon :color="row.id === defaultModelId ? '#f59e0b' : undefined">
                  <StarFilled v-if="row.id === defaultModelId" />
                  <Star v-else />
                </el-icon>
              </el-button>
              <!-- 测试中用旋转图标原位替换（不用 :loading，避免按钮变宽导致操作列换行） -->
              <el-button size="small" text title="测试连接" :disabled="testingIds.has(row.id)" @click="handleTest(row)">
                <el-icon v-if="testingIds.has(row.id)" class="is-loading"><Loading /></el-icon>
                <el-icon v-else><Connection /></el-icon>
              </el-button>
              <el-button size="small" text @click="editModel(row)">
                <el-icon><Edit /></el-icon>
              </el-button>
              <el-button size="small" text type="danger" @click="handleDelete(row)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </el-button-group>
          </template>
        </el-table-column>
      </el-table>
      <!-- 统一分页组件（前端分页：切换条数重置页码，翻页由计算属性自动响应） -->
      <AdminPager v-model:page="modelPage" v-model:page-size="modelPageSize"
        :total="filteredModels.length" :total-pages="modelTotalPages"
        @size-change="modelPage = 1" />
    </div>

    <!-- 编辑模型弹窗 -->
    <el-dialog v-model="editVisible" :title="'编辑模型 - ' + (editForm.displayName || editForm.modelId)" width="520px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="厂商">
          <span style="display:flex;align-items:center;gap:8px;">
            <img v-if="getModelIcon(editForm)" :src="getModelIcon(editForm)" style="width:20px;height:20px;border-radius:4px;" />
            {{ editForm.providerName || editForm.providerId }}
          </span>
        </el-form-item>
        <div class="form-section-divider"></div>
        <el-form-item label="协议格式">
          <span style="font-size:12px;color:#ef4444;">{{ formatProtocol(editForm.protocol) }}</span>
        </el-form-item>
        <el-form-item label="API 地址">
          <el-input v-model="editForm.apiUrl" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="editForm.apiKey" placeholder="不修改则留空" />
        </el-form-item>
        <el-form-item label="模型名称">
          <el-input v-model="editForm.displayName" />
        </el-form-item>
        <el-form-item label="模型ID">
          <el-input v-model="editForm.modelId" disabled />
        </el-form-item>
        <div class="form-section-divider"></div>
        <el-form-item label="输入价格">
          <el-input-number v-model="editForm.inputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" />
          <span class="pricing-hint">人民币元 / 百万 Token</span>
        </el-form-item>
        <el-form-item label="输出价格">
          <el-input-number v-model="editForm.outputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" />
        </el-form-item>
        <el-form-item label="缓存价格">
          <el-input-number v-model="editForm.cachedPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" />
        </el-form-item>
        <el-form-item label="思考价格">
          <el-input-number v-model="editForm.reasoningPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" />
          <span class="pricing-hint">填 0 时按输出价格计算</span>
        </el-form-item>
        <div class="form-section-divider"></div>
        <el-form-item label="状态">
          <el-switch v-model="editForm.enabled" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-switch v-model="editForm.visibleToAll" active-text="所有人" inactive-text="仅管理员" />
        </el-form-item>
        <el-form-item label="思考模式">
          <el-switch v-model="editForm.supportsThinking" />
        </el-form-item>
        <el-form-item label="多模态">
          <el-switch v-model="editForm.supportsMultimodal" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveModel" :loading="submitting">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加模型弹窗 -->
    <el-dialog v-model="addVisible" title="添加模型" width="540px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="厂商">
          <el-select v-model="addForm.providerId" filterable style="width:100%" @change="onProviderChange">
            <el-option v-for="p in allProviders" :key="p.id" :label="p.name + (p.type === 'custom' ? '（自定义）' : '')" :value="p.id" />
            <el-option label="新建自定义厂商..." value="__custom__" />
          </el-select>
        </el-form-item>
        <!-- 自定义厂商名称 -->
        <el-form-item v-if="addForm.providerId === '__custom__'" label="厂商名称">
          <el-input v-model="addForm.customProviderName" placeholder="如：OpenAI" />
        </el-form-item>
        <!-- 已有厂商模型选择 -->
        <el-form-item v-if="addForm.providerId !== '__custom__' && addForm.providerId" label="模型">
          <el-select v-model="addForm.modelSelect" filterable style="width:100%" @change="onModelSelectChange">
            <el-option v-for="pm in currentProviderModels" :key="pm.id" :label="pm.name + (pm.supportsThinking ? ' (思考)' : '') + (pm.supportsMultimodal ? ' (多模态)' : '') + (isModelAdded(pm.id) ? '（已接入）' : '')" :value="pm.id" />
            <el-option label="自定义模型..." value="__custom__" />
          </el-select>
        </el-form-item>

        <div class="form-section-divider"></div>

        <!-- 协议格式 -->
        <el-form-item v-if="addForm.providerId === '__custom__'" label="协议格式">
          <el-select v-model="addForm.protocol" style="width:100%">
            <el-option label="OpenAI 兼容" value="openai" />
            <el-option label="Anthropic" value="anthropic" />
          </el-select>
        </el-form-item>
        <el-form-item v-else-if="addForm.providerId" label="协议格式">
          <span style="font-size:12px;color:#ef4444;">{{ formatProtocol(currentProvider?.protocol) }}</span>
        </el-form-item>

        <!-- 预设模型字段 -->
        <template v-if="isPresetMode">
          <el-form-item label="API 地址">
            <el-input v-model="addForm.apiUrl" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="addForm.apiKey" placeholder="sk-..." />
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input v-model="addForm.displayName" />
          </el-form-item>
          <el-form-item label="模型ID">
            <el-input v-model="addForm.modelId" disabled />
          </el-form-item>
          <el-form-item label="输入价格"><el-input-number v-model="addForm.inputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="输出价格"><el-input-number v-model="addForm.outputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="缓存价格"><el-input-number v-model="addForm.cachedPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="思考价格"><el-input-number v-model="addForm.reasoningPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <div class="form-section-divider"></div>
          <el-form-item label="可见性">
            <el-switch v-model="addForm.visibleToAll" active-text="所有人" inactive-text="仅管理员" />
          </el-form-item>
          <el-form-item label="能力">
            <div style="display:flex;gap:8px;flex-wrap:wrap;">
              <span :style="{ fontSize:'13px', padding:'4px 12px', borderRadius:'6px', background: selectedPresetModel?.supportsThinking ? 'rgba(16,185,129,.15)' : 'rgba(100,116,139,.1)', color: selectedPresetModel?.supportsThinking ? '#10b981' : '#64748b' }">
                思考模式：{{ selectedPresetModel?.supportsThinking ? '支持' : '不支持' }}
              </span>
              <span :style="{ fontSize:'13px', padding:'4px 12px', borderRadius:'6px', background: selectedPresetModel?.supportsMultimodal ? 'rgba(99,102,241,.15)' : 'rgba(100,116,139,.1)', color: selectedPresetModel?.supportsMultimodal ? '#818cf8' : '#64748b' }">
                多模态：{{ selectedPresetModel?.supportsMultimodal ? '支持' : '不支持' }}
              </span>
            </div>
          </el-form-item>
        </template>

        <!-- 自定义模型字段 -->
        <template v-else>
          <el-form-item label="API 地址">
            <el-input v-model="addForm.apiUrl" placeholder="API地址" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input v-model="addForm.apiKey" placeholder="sk-..." />
          </el-form-item>
          <el-form-item label="模型名称">
            <el-input v-model="addForm.displayName" placeholder="可选，默认使用模型ID" />
          </el-form-item>
          <el-form-item label="模型ID">
            <el-input v-model="addForm.modelId" placeholder="如 gpt-4o" />
          </el-form-item>
          <el-form-item label="输入价格"><el-input-number v-model="addForm.inputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="输出价格"><el-input-number v-model="addForm.outputPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="缓存价格"><el-input-number v-model="addForm.cachedPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <el-form-item label="思考价格"><el-input-number v-model="addForm.reasoningPriceCny" :min="0" :precision="6" :step="0.1" style="width:100%" /></el-form-item>
          <div class="form-section-divider"></div>
          <el-form-item label="可见性">
            <el-switch v-model="addForm.visibleToAll" active-text="所有人" inactive-text="仅管理员" />
          </el-form-item>
          <el-form-item label="思考模式">
            <el-switch v-model="addForm.supportsThinking" />
          </el-form-item>
          <el-form-item label="多模态">
            <el-switch v-model="addForm.supportsMultimodal" />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAddModel" :loading="submitting">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Star, StarFilled, Connection, Loading, ArrowUp, ArrowDown } from '@element-plus/icons-vue'
import { getModels, addModel, updateModel, deleteModel, batchDeleteModels, testModel, setDefaultModel, clearDefaultModel } from '@/api/models'
import { getProviders } from '@/api/providers'
import { autoColWidth } from '@/composables/useTableAutoWidth'
import AdminPager from '@/components/admin/AdminPager.vue'

const providerIconMap = {
  deepseek: '/icons/deepseek-icon.svg',
  qwen: '/icons/qwen-icon.svg',
  kimi: '/icons/kimi-icon.svg',
  zhipu: '/icons/zhipu-icon.svg',
  minimax: '/icons/minimax-icon.svg',
  doubao: '/icons/doubao-icon.svg'
}

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const allModels = ref([])
const allProviders = ref([])
const defaultModelId = ref(null)
const tableRef = ref(null)
const selectedRows = ref([])

// 筛选
const filters = ref({ name: '', providerId: '', modelId: '', thinking: '', multimodal: '', enabled: '', visible: '' })
// 手机端筛选条件展开状态（默认收起，仅展示前两个条件）
const filterExpanded = ref(false)

const providerFilterOptions = computed(() => {
  const map = {}
  allModels.value.forEach(m => {
    const pid = m.providerId || '__custom__'
    if (!map[pid]) map[pid] = m.providerName || pid
  })
  return Object.keys(map).sort().map(k => ({ value: k, label: map[k] }))
})

const modelIdFilterOptions = computed(() => {
  const seen = {}
  const opts = []
  allModels.value.forEach(m => {
    if (m.modelId && !seen[m.modelId]) {
      seen[m.modelId] = true
      opts.push({ value: m.modelId, label: m.displayName || m.modelId })
    }
  })
  return opts
})

const modelPage = ref(1)
const modelPageSize = ref(10)

const filteredModels = computed(() => {
  return allModels.value.filter(m => {
    if (filters.value.name) {
      const nm = (m.displayName || m.modelId || '').toLowerCase()
      if (!nm.includes(filters.value.name.toLowerCase())) return false
    }
    if (filters.value.providerId && (m.providerId || '__custom__') !== filters.value.providerId) return false
    if (filters.value.modelId && m.modelId !== filters.value.modelId) return false
    if (filters.value.thinking !== '' && filters.value.thinking !== null) {
      if ((m.supportsThinking ? '1' : '0') !== filters.value.thinking) return false
    }
    if (filters.value.multimodal !== '' && filters.value.multimodal !== null) {
      if ((m.supportsMultimodal ? '1' : '0') !== filters.value.multimodal) return false
    }
    if (filters.value.enabled !== '' && filters.value.enabled !== null) {
      if ((m.enabled ? '1' : '0') !== filters.value.enabled) return false
    }
    if (filters.value.visible !== '' && filters.value.visible !== null) {
      if ((m.visibleToAll ? '1' : '0') !== filters.value.visible) return false
    }
    return true
  })
})

const pagedModels = computed(() => {
  const start = (modelPage.value - 1) * modelPageSize.value
  return filteredModels.value.slice(start, start + modelPageSize.value)
})
const modelTotalPages = computed(() => Math.max(1, Math.ceil(filteredModels.value.length / modelPageSize.value)))

// 列宽自适应：按当前列最长内容计算，上限 50 个汉字
const colW = computed(() => {
  const list = allModels.value
  return {
    // 名称列：图标(20+间距8)与徽章内边距 extra 40，默认模型行额外计入“默认”徽章文本宽度
    name: autoColWidth(
      list.map(m => (m.displayName || m.modelId || '') + (m.id === defaultModelId.value ? '　默认' : '')),
      { header: '名称', extra: 40, min: 120 }
    ),
    provider: autoColWidth(list.map(m => m.providerName), { header: '厂商' }),
    // 模型ID列以等宽字体渲染，按 mono 测宽
    modelId: autoColWidth(list.map(m => m.modelId), { header: '模型', min: 100, mono: true }),
    thinking: autoColWidth(['支持', '不支持'], { header: '思考', extra: 16 }),
    multimodal: autoColWidth(['支持', '不支持'], { header: '多模态', extra: 16 }),
    status: autoColWidth(['已启用', '已禁用'], { header: '状态', extra: 24 }),
    visible: autoColWidth(['所有人', '仅管理员'], { header: '可见性', extra: 24 }),
    latency: autoColWidth(list.map(m => m.testLatencyMs != null ? `${m.testLatencyMs} ms` : '-'), { header: '延迟', mono: true }),
    speed: autoColWidth(list.map(m => m.testSpeed != null ? `${m.testSpeed} tk/s` : '-'), { header: '速度', mono: true })
  }
})

function applyFilter() { modelPage.value = 1 }
function resetFilter() {
  filters.value = { name: '', providerId: '', modelId: '', thinking: '', multimodal: '', enabled: '', visible: '' }
  modelPage.value = 1
}

function getModelIcon(model) {
  if (model.providerId && providerIconMap[model.providerId]) return providerIconMap[model.providerId]
  return null
}

// 延迟颜色分级：<800ms 绿色，800ms~2s 黄色，>2s 红色
function latencyClass(ms) {
  if (ms < 800) return 'latency-good'
  if (ms <= 2000) return 'latency-warn'
  return 'latency-bad'
}

function formatProtocol(protocol) {
  return protocol === 'anthropic' ? 'Anthropic' : 'OpenAI 兼容'
}

// 切换启用/禁用
async function toggleModel(row) {
  const res = await updateModel(row.id, { ...row, enabled: !row.enabled })
  if (res?.success) {
    ElMessage.success(!row.enabled ? '已启用' : '已禁用')
    await loadData()
  }
}

// 设置/取消默认
async function handleDefault(row) {
  if (row.id === defaultModelId.value) {
    const res = await clearDefaultModel()
    if (res?.success) { ElMessage.success(res.message || '已取消默认模型'); await loadData() }
  } else {
    const res = await setDefaultModel(row.id)
    if (res?.success) { ElMessage.success(res.message || '已设为默认模型'); await loadData() }
  }
}

// 删除
async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除模型 "${row.displayName || row.modelId}" 吗？`, '确认删除', { type: 'warning' })
    const res = await deleteModel(row.id)
    if (res?.success) { ElMessage.success('已删除'); await loadData() }
    else ElMessage.error(res?.message || '删除失败')
  } catch { /* cancelled */ }
}

function onSelectionChange(rows) {
  selectedRows.value = rows
}

// 批量删除选中模型
async function handleDeleteSelected() {
  const rows = selectedRows.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${rows.length} 个模型吗？`, '确认批量删除', { type: 'warning' })
    const res = await batchDeleteModels(rows.map(r => r.id))
    if (res?.success) {
      ElMessage.success(`已删除 ${res.deleted ?? rows.length} 个模型`)
      tableRef.value?.clearSelection()
      await loadData()
    } else {
      ElMessage.error(res?.message || '删除失败')
    }
  } catch { /* cancelled */ }
}

// 连通性测试：仅用户手动触发，测试完成后刷新列表展示最新延迟/速度
// 支持连点不同模型的测试按钮：用 Set 记录正在测试的模型 id，多个模型可并发测试，各行独立展示测试中状态
const testingIds = reactive(new Set())
async function handleTest(row) {
  if (testingIds.has(row.id)) return
  testingIds.add(row.id)
  try {
    const res = await testModel(row.id)
    if (res?.success) {
      ElMessage.success(res.message || '连接成功')
    } else {
      ElMessage.error({ message: `连接失败：${res?.message || '未知错误'}`, duration: 6000 })
    }
    // 后端已持久化最新测试指标（失败时清空），刷新列表同步展示
    await loadData()
  } catch { /* 拦截器已提示 */ } finally {
    testingIds.delete(row.id)
  }
}

// ===== 编辑模型 =====
const editVisible = ref(false)
const editForm = ref({})

function editModel(row) {
  editForm.value = { ...row }
  editVisible.value = true
}

async function saveModel() {
  submitting.value = true
  try {
    const payload = { ...editForm.value }
    if (!payload.apiKey || payload.apiKey.includes('*')) delete payload.apiKey
    const res = await updateModel(editForm.value.id, payload)
    if (res?.success) {
      ElMessage.success('保存成功')
      editVisible.value = false
      await loadData()
    } else {
      ElMessage.error(res?.message || '保存失败')
    }
  } finally {
    submitting.value = false
  }
}

// ===== 添加模型 =====
const addVisible = ref(false)
const addForm = ref({
  providerId: '',
  customProviderName: '',
  modelSelect: '',
  protocol: 'openai',
  apiUrl: '',
  apiKey: '',
  displayName: '',
  modelId: '',
  visibleToAll: true,
  supportsThinking: false,
  supportsMultimodal: false,
  inputPriceCny: 0,
  outputPriceCny: 0,
  cachedPriceCny: 0,
  reasoningPriceCny: 0
})

const currentProvider = computed(() => allProviders.value.find(p => p.id === addForm.value.providerId))
const currentProviderModels = computed(() => currentProvider.value?.models || [])
const selectedPresetModel = computed(() => currentProviderModels.value.find(pm => pm.id === addForm.value.modelSelect))
const isPresetMode = computed(() => addForm.value.providerId !== '__custom__' && addForm.value.modelSelect && addForm.value.modelSelect !== '__custom__')

function isModelAdded(modelId) {
  return allModels.value.some(m => m.providerId === addForm.value.providerId && m.modelId === modelId)
}

function showAddModel() {
  addForm.value = { providerId: '', customProviderName: '', modelSelect: '', protocol: 'openai', apiUrl: '', apiKey: '', displayName: '', modelId: '', visibleToAll: true, supportsThinking: false, supportsMultimodal: false, inputPriceCny: 0, outputPriceCny: 0, cachedPriceCny: 0, reasoningPriceCny: 0 }
  addVisible.value = true
}

function onProviderChange() {
  addForm.value.modelSelect = ''
  addForm.value.apiUrl = ''
  addForm.value.apiKey = ''
  addForm.value.displayName = ''
  addForm.value.modelId = ''
  if (addForm.value.providerId !== '__custom__' && currentProvider.value) {
    addForm.value.apiUrl = currentProvider.value.defaultApiUrl || ''
    addForm.value.protocol = currentProvider.value.protocol || 'openai'
    // 自定义厂商默认进入“自定义模型”，预置厂商默认选择目录第一项
    const models = currentProvider.value.models || []
    if (currentProvider.value.type !== 'custom' && models.length > 0) {
      addForm.value.modelSelect = models[0].id
      onModelSelectChange(models[0].id)
    } else {
      addForm.value.modelSelect = '__custom__'
      onModelSelectChange('__custom__')
    }
  }
}

function onModelSelectChange(val) {
  if (val === '__custom__') {
    addForm.value.displayName = ''
    addForm.value.modelId = ''
    addForm.value.supportsThinking = false
    addForm.value.supportsMultimodal = false
  } else {
    const pm = currentProviderModels.value.find(m => m.id === val)
    if (pm) {
      addForm.value.displayName = pm.name
      addForm.value.modelId = pm.id
      addForm.value.apiUrl = currentProvider.value?.defaultApiUrl || ''
    }
  }
}

async function submitAddModel() {
  const isCustomProvider = addForm.value.providerId === '__custom__'
  let payload

  if (isCustomProvider) {
    if (!addForm.value.customProviderName.trim()) { ElMessage.warning('请输入厂商名称'); return }
    if (!addForm.value.modelId.trim()) { ElMessage.warning('请输入模型ID'); return }
    if (!addForm.value.apiKey.trim()) { ElMessage.warning('请输入 API Key'); return }
    payload = {
      providerId: '__custom__',
      modelId: addForm.value.modelId.trim(),
      displayName: addForm.value.displayName.trim() || addForm.value.modelId.trim(),
      providerName: addForm.value.customProviderName.trim(),
      apiUrl: addForm.value.apiUrl.trim(),
      apiKey: addForm.value.apiKey.trim(),
      protocol: addForm.value.protocol,
      visibleToAll: addForm.value.visibleToAll,
      supportsThinking: addForm.value.supportsThinking,
      supportsMultimodal: addForm.value.supportsMultimodal,
      enabled: true,
      inputPriceCny: addForm.value.inputPriceCny || 0,
      outputPriceCny: addForm.value.outputPriceCny || 0,
      cachedPriceCny: addForm.value.cachedPriceCny || 0,
      reasoningPriceCny: addForm.value.reasoningPriceCny || 0
    }
  } else if (isPresetMode.value) {
    if (!addForm.value.apiKey.trim()) { ElMessage.warning('请输入 API Key'); return }
    const pm = selectedPresetModel.value
    if (!pm) { ElMessage.warning('请选择模型'); return }
    payload = {
      providerId: addForm.value.providerId,
      modelId: pm.id,
      displayName: addForm.value.displayName.trim() || pm.name,
      providerName: currentProvider.value.name,
      providerIcon: currentProvider.value.icon,
      apiUrl: addForm.value.apiUrl.trim() || currentProvider.value.defaultApiUrl,
      apiKey: addForm.value.apiKey.trim(),
      protocol: currentProvider.value.protocol,
      thinkingParamType: currentProvider.value.thinkingParamType,
      visibleToAll: addForm.value.visibleToAll,
      supportsThinking: pm.supportsThinking || false,
      supportsMultimodal: pm.supportsMultimodal || false,
      enabled: true,
      inputPriceCny: addForm.value.inputPriceCny || 0,
      outputPriceCny: addForm.value.outputPriceCny || 0,
      cachedPriceCny: addForm.value.cachedPriceCny || 0,
      reasoningPriceCny: addForm.value.reasoningPriceCny || 0
    }
  } else {
    // 已有厂商 + 自定义模型；后端按 providerId 自动补齐厂商名称、图标、API 地址与协议
    if (!addForm.value.modelId.trim()) { ElMessage.warning('请输入模型ID'); return }
    if (!addForm.value.apiKey.trim()) { ElMessage.warning('请输入 API Key'); return }
    payload = {
      providerId: addForm.value.providerId,
      modelId: addForm.value.modelId.trim(),
      displayName: addForm.value.displayName.trim() || addForm.value.modelId.trim(),
      providerName: currentProvider.value?.name,
      providerIcon: currentProvider.value?.icon,
      apiUrl: addForm.value.apiUrl.trim() || currentProvider.value?.defaultApiUrl,
      apiKey: addForm.value.apiKey.trim(),
      protocol: currentProvider.value?.protocol,
      thinkingParamType: currentProvider.value?.thinkingParamType,
      visibleToAll: addForm.value.visibleToAll,
      supportsThinking: addForm.value.supportsThinking,
      supportsMultimodal: addForm.value.supportsMultimodal,
      enabled: true,
      inputPriceCny: addForm.value.inputPriceCny || 0,
      outputPriceCny: addForm.value.outputPriceCny || 0,
      cachedPriceCny: addForm.value.cachedPriceCny || 0,
      reasoningPriceCny: addForm.value.reasoningPriceCny || 0
    }
  }

  submitting.value = true
  try {
    const res = await addModel(payload)
    if (res?.success) {
      ElMessage.success('添加成功')
      addVisible.value = false
      await loadData()
    } else {
      ElMessage.error(res?.message || '添加失败')
    }
  } finally {
    submitting.value = false
  }
}

// 加载模型与厂商数据；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadData(silent = false) {
  if (!silent) loading.value = true
  try {
    const [mRes, pRes] = await Promise.all([getModels(), getProviders()])
    if (mRes?.success) {
      allModels.value = mRes.data || []
      defaultModelId.value = mRes.defaultModelId || null
    }
    if (pRes?.success) allProviders.value = pRes.data || []
  } finally {
    loading.value = false
  }
}

// 接收快速接入页厂商卡片下钻的过滤参数，按厂商过滤模型列表
// （keep-alive 缓存下组件不再重复挂载，故需在每次激活时检查而非仅 onMounted）
function applyProviderDrilldown() {
  const pid = route.query.providerId
  if (pid) {
    filters.value.providerId = String(pid)
    applyFilter()
    // 清除 query，避免刷新或返回时重复应用过滤
    router.replace({ path: '/admin/models' })
  }
}

onMounted(async () => {
  await loadData()
  applyProviderDrilldown()
})

// keep-alive 缓存下再次进入本页时静默刷新数据并重新检查下钻参数；
// 首次挂载由 onMounted 负责加载，跳过第一次激活避免重复请求
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  loadData(true)
  applyProviderDrilldown()
})
</script>
