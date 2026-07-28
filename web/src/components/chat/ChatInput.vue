<template>
  <div class="input-area">
    <!-- 图片预览区 -->
    <div v-if="pendingImages.length" class="image-preview-area">
      <div v-for="(img, idx) in pendingImages" :key="idx" class="image-preview-item">
        <img :src="img" alt="粘贴图片">
        <button class="image-preview-remove" @click="removeImage(idx)" title="删除">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>
    </div>
    <div v-if="pasteWarning" class="paste-warning">{{ pasteWarning }}</div>

    <div class="input-row">
      <div class="input-textarea-wrap">
        <div class="textarea-meta">
          <span class="meta-tag">USER · INPUT</span>
        </div>
        <textarea
          ref="textareaRef"
          v-model="inputText"
          placeholder="写下你正在想的事 · Press Enter to send"
          rows="1"
          @input="autoResize"
          @keydown="handleKeydown"
          @paste="handlePaste"
        ></textarea>

        <!-- 输入框内工具栏 -->
        <div class="input-inner-toolbar">
          <div v-if="supportsThinking" class="think-icon-btn" :class="{ active: deepThinking }" @click="deepThinking = !deepThinking" title="深度思考">
            <img :src="thinkIconSrc" style="width:14px;height:14px;" />
          </div>
          <span v-if="supportsThinking" class="toolbar-divider"></span>
          <div class="upload-image-btn" title="上传图片" @click="triggerUpload">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><polyline points="21 15 16 10 5 21"/></svg>
          </div>
          <div class="upload-image-btn" title="清除上下文（后续对话不再携带以上历史）" @click="emit('clear-context')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"/></svg>
          </div>
          <input type="file" ref="fileInputRef" accept="image/*" multiple style="display:none" @change="handleFileUpload">
          <div class="model-select-area">
            <img v-if="currentIconIsImg" :src="currentIcon" class="model-area-icon" />
            <span v-else-if="currentIcon" class="model-area-icon model-area-emoji">{{ currentIcon }}</span>
            <el-cascader
              v-model="cascaderValue"
              :options="cascaderOptions"
              :props="cascaderProps"
              :show-all-levels="false"
              :style="{ width: modelInputWidth }"
              :filterable="!isMobile"
              :key="'cascader-' + (isMobile ? 'mobile' : 'desktop')"
              placement="top"
              popper-class="model-cascader-popper"
              @change="handleModelChange"
              @visible-change="onCascaderVisibleChange"
            >
              <template #default="{ data }">
                <img v-if="data.icon && data.icon.startsWith('/')" :src="data.icon" class="cascader-node-icon" />
                <span v-else-if="data.icon" class="cascader-node-icon cascader-node-emoji">{{ data.icon }}</span>
                <span class="cascader-node-label">{{ data.label }}</span>
              </template>
            </el-cascader>
          </div>
          <button class="send-btn" :class="{ stop: isStreaming }" @click="handleSendClick" :title="isStreaming ? '停止' : '发送'">
            <svg v-if="!isStreaming" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
          </button>
        </div>
      </div>
    </div>
    <div class="input-footer">
      <span class="foot-l">⌘/Ctrl+Enter · 新行</span>
      <span class="foot-c">内容由 AI 生成 · 请仔细甄别</span>
      <span class="foot-r">v{{ APP_VERSION }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useModelsStore } from '@/stores/models'
import { useTheme } from '@/composables/useTheme'
import { ElMessage } from 'element-plus'
import { APP_VERSION } from '@/config/version'
import { uploadChatImage } from '@/api/chat'

const props = defineProps({
  isStreaming: { type: Boolean, default: false },
  supportsThinking: { type: Boolean, default: false },
  supportsMultimodal: { type: Boolean, default: false }
})

const emit = defineEmits(['send', 'stop', 'clear-context'])

const modelsStore = useModelsStore()
const { getTheme } = useTheme()

const inputText = ref('')
const deepThinking = ref(false)
const pendingImages = ref([])
const pasteWarning = ref('')
const textareaRef = ref(null)
const fileInputRef = ref(null)

const MAX_IMAGE_SIZE = 5 * 1024 * 1024

// 移动端判定：窄屏(≤768px)关闭 el-cascader 的 filterable，
// 避免点击模型选择器时内部搜索 input 获焦而唤起手机软键盘
const isMobile = ref(typeof window !== 'undefined' && window.innerWidth <= 768)
function handleResize() {
  isMobile.value = window.innerWidth <= 768
}
onMounted(() => window.addEventListener('resize', handleResize))
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  // 组件卸载时确保恢复背景滚动，防止浮窗未关闭就卸载导致锁定残留
  const container = document.querySelector('.chat-container')
  if (container) {
    container.style.overflow = ''
    container.style.touchAction = ''
  }
  document.body.style.touchAction = ''
})

const thinkIconSrc = computed(() => {
  return getTheme() === 'dark' ? '/icons/icon_深度思考_ss.svg' : '/icons/icon_深度思考.svg'
})

// ===== 模型级联选择器 =====
const cascaderValue = ref([])
const cascaderProps = {
  // 采用 click 触发展开而非 hover：避免鼠标在厂商节点间移动时第二列内容实时切换，
  // 导致浮窗宽高反复重算、popper 不断重新定位而产生的抖动；同时移动端触屏体验更自然
  expandTrigger: 'click',
  value: 'value',
  label: 'label',
  children: 'children'
}

// 厂商图标映射（与后台管理页一致）
const providerIconMap = {
  deepseek: '/icons/deepseek-icon.svg',
  qwen: '/icons/qwen-icon.svg',
  kimi: '/icons/kimi-icon.svg',
  zhipu: '/icons/zhipu-icon.svg',
  minimax: '/icons/minimax-icon.svg',
  doubao: '/icons/doubao-icon.svg'
}
function resolveGroupIcon(group) {
  if (group.providerId && providerIconMap[group.providerId]) return providerIconMap[group.providerId]
  if (group.providerIcon) return group.providerIcon
  return ''
}

const cascaderOptions = computed(() => {
  const { groups, order } = modelsStore.groupedModels
  return order.map(key => {
    const g = groups[key]
    const icon = resolveGroupIcon(g)
    return {
      value: key,
      label: g.name,
      icon,
      children: g.models.map(m => ({
        value: m.id,
        label: m.displayName,
        icon
      }))
    }
  })
})

// 当前选中模型的厂商图标（触发器前展示）
const currentIcon = computed(() => {
  const id = modelsStore.currentModelId
  if (!id) return ''
  const { groups, order } = modelsStore.groupedModels
  for (const key of order) {
    if (groups[key].models.some(m => m.id === id)) return resolveGroupIcon(groups[key])
  }
  return ''
})
const currentIconIsImg = computed(() => currentIcon.value.startsWith('/'))

// 根据模型名长度动态计算输入框宽度，避免长名称被截断
const modelInputWidth = computed(() => {
  const name = String(modelsStore.currentModelName || '选择模型')
  let w = 0
  for (const ch of name) {
    w += /[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]/.test(ch) ? 12.5 : 7
  }
  return Math.min(Math.max(Math.round(w) + 26, 72), 300) + 'px'
})

// 同步当前选中模型到 cascader 显示
function syncCascaderFromStore() {
  const id = modelsStore.currentModelId
  if (!id) { cascaderValue.value = []; return }
  const { groups, order } = modelsStore.groupedModels
  for (const key of order) {
    const found = groups[key].models.find(m => m.id === id)
    if (found) { cascaderValue.value = [key, id]; return }
  }
}
watch(() => modelsStore.currentModelId, syncCascaderFromStore, { immediate: true })

function handleModelChange(val) {
  if (!val || val.length < 2) return
  // bot 输出未结束前不可切换模型：允许打开选择器浏览，但选中不生效并回退到当前模型
  if (props.isStreaming) {
    ElMessage.warning('回复生成中，暂不可切换模型')
    syncCascaderFromStore()
    return
  }
  const modelId = val[1]
  const model = modelsStore.findModelById(modelId)
  if (model) modelsStore.selectModel(model)
  // 切换模型后重置深度思考开关（与旧版一致，避免上一模型的思考状态带到新模型）
  deepThinking.value = false
}

// 移动端：模型选择浮窗弹出时锁定背景(.chat-container)与 body 的触摸滚动，
// 避免触摸浮窗外区域时背景页面可上下左右滑动；关闭后恢复原滚动位置
let savedContainerScrollTop = 0
function onCascaderVisibleChange(visible) {
  if (!isMobile.value) return
  const container = document.querySelector('.chat-container')
  if (visible) {
    if (container) {
      savedContainerScrollTop = container.scrollTop
      container.style.overflow = 'hidden'
      container.style.touchAction = 'none'
    }
    document.body.style.touchAction = 'none'
  } else {
    if (container) {
      container.style.overflow = ''
      container.style.touchAction = ''
      container.scrollTop = savedContainerScrollTop
    }
    document.body.style.touchAction = ''
  }
}

function autoResize() {
  const ta = textareaRef.value
  if (!ta) return
  ta.style.height = 'auto'
  ta.style.height = Math.min(ta.scrollHeight, 150) + 'px'
}

function handleKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    doSend()
  }
}

function handleSendClick() {
  if (props.isStreaming) {
    emit('stop')
  } else {
    doSend()
  }
}

function doSend() {
  const text = inputText.value.trim()
  const hasImages = pendingImages.value.length > 0
  if (hasImages && !props.supportsMultimodal) {
    ElMessage.warning('当前模型不支持图像理解，请删除图片后再发送')
    return
  }
  if (!text && !hasImages) return
  emit('send', { text, images: pendingImages.value.slice(), deepThinking: deepThinking.value })
  inputText.value = ''
  pendingImages.value = []
  pasteWarning.value = ''
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto'
  }
}

function handlePaste(e) {
  const items = e.clipboardData?.items
  if (!items) return
  const imageItems = []
  for (let i = 0; i < items.length; i++) {
    if (items[i].type.includes('image')) imageItems.push(items[i])
  }
  if (imageItems.length === 0) return
  e.preventDefault()
  imageItems.forEach(item => {
    const file = item.getAsFile()
    if (file) addImageFile(file)
  })
}

function triggerUpload() {
  if (fileInputRef.value) {
    fileInputRef.value.value = ''
    fileInputRef.value.click()
  }
}

function handleFileUpload(e) {
  const files = e.target.files
  if (!files || files.length === 0) return
  Array.from(files).forEach(file => {
    if (!file.type.includes('image')) return
    addImageFile(file)
  })
}

// 上传图片到服务端，成功后以文件 URL 加入待发送列表（替代原 base64 内嵌）
async function addImageFile(file) {
  if (file.size > MAX_IMAGE_SIZE) {
    ElMessage.warning('图片超过5MB限制')
    return
  }
  try {
    const res = await uploadChatImage(file)
    if (res && res.success) {
      if (!props.supportsMultimodal) {
        pasteWarning.value = '当前模型不支持图像理解，请切换支持多模态的模型或删除图片'
      }
      pendingImages.value.push(res.url)
    } else {
      ElMessage.error((res && res.message) || '图片上传失败')
    }
  } catch (e) {
    // 错误提示已由 request 拦截器统一处理
  }
}

function removeImage(idx) {
  pendingImages.value.splice(idx, 1)
  if (pendingImages.value.length === 0) pasteWarning.value = ''
}
</script>
