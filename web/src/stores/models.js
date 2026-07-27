import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchModels } from '@/api/chat'

export const useModelsStore = defineStore('models', () => {
  const models = ref([])
  const defaultModelId = ref(null)
  const currentModelId = ref('')
  const currentModelName = ref('')
  const currentModelSupportsMultimodal = ref(false)
  const currentModelSupportsThinking = ref(false)

  // 按厂商分组
  const groupedModels = computed(() => {
    const groups = {}
    const order = []
    models.value.forEach(m => {
      const key = m.providerName || m.providerId || 'custom'
      if (!groups[key]) {
        groups[key] = {
          key,
          name: key,
          providerId: m.providerId || inferProviderId(m),
          providerIcon: m.providerIcon || '',
          models: []
        }
        order.push(key)
      }
      groups[key].models.push(m)
    })
    // 同一组内，providerIcon 取第一个非空的
    order.forEach(k => {
      const g = groups[k]
      if (!g.providerIcon) {
        for (let i = 0; i < g.models.length; i++) {
          if (g.models[i].providerIcon) {
            g.providerIcon = g.models[i].providerIcon
            break
          }
        }
      }
    })
    return { groups, order }
  })

  const currentModel = computed(() => {
    return models.value.find(m => m.id === currentModelId.value) || null
  })

  function inferProviderId(model) {
    if (model.providerId) return model.providerId
    const name = (model.displayName || '').toLowerCase()
    const id = (model.modelId || '').toLowerCase()
    if (name.includes('deepseek') || id.includes('deepseek')) return 'deepseek'
    if (name.includes('qwen') || name.includes('通义') || id.includes('qwen') || id.includes('qwq')) return 'qwen'
    if (name.includes('kimi') || name.includes('moonshot') || id.includes('kimi') || id.includes('moonshot')) return 'kimi'
    if (name.includes('glm') || name.includes('智谱') || id.includes('glm')) return 'zhipu'
    if (name.includes('minimax') || id.includes('minimax')) return 'minimax'
    if (name.includes('豆包') || name.includes('doubao') || id.includes('doubao')) return 'doubao'
    return ''
  }

  function findModelById(id) {
    if (!id) return null
    return models.value.find(m => m.id === id) || null
  }

  async function loadModels() {
    try {
      const data = await fetchModels()
      if (data && data.success) {
        models.value = data.data || []
        defaultModelId.value = data.defaultModelId || null
        // 初始模型：优先默认模型，否则取第一个
        const initial = (models.value.length > 0)
          ? (findModelById(defaultModelId.value) || models.value[0])
          : null
        if (initial) {
          selectModel(initial)
        }
      }
    } catch (e) {
      console.error('加载模型失败:', e)
    }
  }

  function selectModel(model) {
    currentModelId.value = model.id
    currentModelName.value = model.displayName
    currentModelSupportsMultimodal.value = model.supportsMultimodal || false
    currentModelSupportsThinking.value = model.supportsThinking || false
  }

  function applyDefaultModel() {
    const dm = findModelById(defaultModelId.value)
    if (dm) selectModel(dm)
  }

  return {
    models, defaultModelId, currentModelId, currentModelName,
    currentModelSupportsMultimodal, currentModelSupportsThinking,
    groupedModels, currentModel,
    loadModels, selectModel, applyDefaultModel, findModelById, inferProviderId
  }
})
