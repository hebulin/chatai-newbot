<template>
  <div>
    <div class="section-header">
      <div class="section-title">
        <span class="section-eyebrow">01 / QUICK START · 快速接入</span>
        <h2>快速接入模型</h2>
      </div>
      <span class="section-tip">选择厂商，填入 API Key 即可一键接入所有模型</span>
    </div>

    <div class="provider-grid" v-loading="loading">
      <div
        v-for="p in presetProviders"
        :key="p.id"
        class="provider-card"
        :class="{ active: selectedProvider === p.id }"
        @click="onCardClick(p)"
      >
        <div class="provider-card-icon">
          <img :src="providerIconMap[p.id]" v-if="providerIconMap[p.id]" />
          <span v-else style="font-size: 20px; color: var(--ink-3)">{{ p.name?.[0] }}</span>
        </div>
        <div class="provider-card-info">
          <div class="provider-card-name">{{ p.name }}</div>
          <div class="provider-card-count">已接入 {{ getExistingCount(p.id) }} / {{ getTotalModels(p) }} 个模型</div>
        </div>
        <div>
          <el-button
            v-if="!isAllAdded(p)"
            type="primary"
            size="small"
            @click.stop="showQuickAdd(p)"
          >一键接入</el-button>
          <span v-else class="provider-card-done">✓ 已全部接入</span>
        </div>
      </div>
      <div v-if="!loading && presetProviders.length === 0" style="text-align:center;color:var(--ink-3);padding:40px;grid-column:1/-1;">
        暂无厂商数据
      </div>
    </div>

    <!-- 快速接入弹窗 -->
    <el-dialog v-model="quickAddVisible" :title="'快速接入 - ' + (currentProvider?.name || '')" width="500px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="厂商">
          <span style="display:flex;align-items:center;gap:8px;">
            <img v-if="providerIconMap[currentProvider?.id]" :src="providerIconMap[currentProvider?.id]" style="width:20px;height:20px;border-radius:4px;" />
            {{ currentProvider?.name }}
          </span>
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="quickAddForm.apiKey" placeholder="sk-..." />
        </el-form-item>
        <el-form-item label="选择模型">
          <div style="display:flex;flex-direction:column;gap:8px;max-height:240px;overflow-y:auto;width:100%;">
            <el-checkbox
              v-for="pm in availableModels"
              :key="pm.id"
              v-model="quickAddForm.selectedIds[pm.id]"
            >
              {{ pm.name }}
              <span v-if="pm.supportsThinking" class="think-badge" style="margin-left:4px;">思考</span>
              <span v-if="pm.supportsMultimodal" class="mm-badge" style="margin-left:4px;">多模态</span>
            </el-checkbox>
          </div>
        </el-form-item>
        <el-form-item label="可见性">
          <el-switch v-model="quickAddForm.visibleToAll" active-text="所有人" inactive-text="仅管理员" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="quickAddVisible = false">取消</el-button>
        <el-button type="primary" @click="submitQuickAdd" :loading="submitting">确认接入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProviders } from '@/api/providers'
import { getModels, batchAddModels } from '@/api/models'

const providerIconMap = {
  deepseek: '/icons/deepseek-icon.svg',
  qwen: '/icons/qwen-icon.svg',
  kimi: '/icons/kimi-icon.svg',
  zhipu: '/icons/zhipu-icon.svg',
  minimax: '/icons/minimax-icon.svg',
  doubao: '/icons/doubao-icon.svg'
}

const router = useRouter()
const loading = ref(false)
const submitting = ref(false)
const allProviders = ref([])
const allModels = ref([])
const selectedProvider = ref(null)

const presetProviders = computed(() => allProviders.value.filter(p => p.type !== 'custom'))

const quickAddVisible = ref(false)
const currentProvider = ref(null)
const quickAddForm = ref({
  apiKey: '',
  selectedIds: {},
  visibleToAll: true
})

const availableModels = computed(() => {
  if (!currentProvider.value) return []
  const pModels = currentProvider.value.models || []
  return pModels.filter(pm => !allModels.value.some(m => m.providerId === currentProvider.value.id && m.modelId === pm.id))
})

function getExistingCount(providerId) {
  return allModels.value.filter(m => m.providerId === providerId).length
}

function getTotalModels(p) {
  return (p.models || []).length || p.modelCount || 0
}

function isAllAdded(p) {
  const total = getTotalModels(p)
  return total > 0 && getExistingCount(p.id) >= total
}

function onCardClick(p) {
  selectedProvider.value = p.id
  // 跳转到模型管理并按该厂商过滤（与旧版下钻导航一致）
  router.push({ path: '/admin/models', query: { providerId: p.id } })
}

function showQuickAdd(p) {
  currentProvider.value = p
  quickAddForm.value = { apiKey: '', selectedIds: {}, visibleToAll: true }
  // 默认全选可用模型
  const pModels = p.models || []
  pModels.forEach(pm => {
    if (!allModels.value.some(m => m.providerId === p.id && m.modelId === pm.id)) {
      quickAddForm.value.selectedIds[pm.id] = true
    }
  })
  quickAddVisible.value = true
}

async function submitQuickAdd() {
  if (!quickAddForm.value.apiKey.trim()) {
    ElMessage.warning('请输入 API Key')
    return
  }
  const selectedModelIds = Object.keys(quickAddForm.value.selectedIds).filter(id => quickAddForm.value.selectedIds[id])
  if (selectedModelIds.length === 0) {
    ElMessage.warning('请至少选择一个模型')
    return
  }
  submitting.value = true
  try {
    const res = await batchAddModels({
      providerId: currentProvider.value.id,
      apiKey: quickAddForm.value.apiKey.trim(),
      selectedModelIds,
      visibleToAll: quickAddForm.value.visibleToAll
    })
    if (res && res.success) {
      ElMessage.success(res.message || '接入成功')
      quickAddVisible.value = false
      await loadData()
    } else {
      ElMessage.error(res?.message || '接入失败')
    }
  } finally {
    submitting.value = false
  }
}

// 加载厂商与模型数据；silent 为 true 时不显示 loading 遮罩（keep-alive 激活刷新用，避免闪烁）
async function loadData(silent = false) {
  if (!silent) loading.value = true
  try {
    const [pRes, mRes] = await Promise.all([getProviders(), getModels()])
    if (pRes?.success) allProviders.value = pRes.data || []
    if (mRes?.success) allModels.value = mRes.data || []
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
