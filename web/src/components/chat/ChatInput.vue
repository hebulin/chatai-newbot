<template>
  <div class="input-area">
    <!-- 图片预览区 -->
    <div v-if="pendingImages.length" class="image-preview-area">
      <div v-for="(img, idx) in pendingImages" :key="idx" class="image-preview-item">
        <img :src="img" :alt="t('messages.sentImage')">
        <button class="image-preview-remove" @click="removeImage(idx)" :title="t('input.delete')">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
        </button>
      </div>
    </div>
    <!-- 附件文档预览区 -->
    <div v-if="pendingFiles.length" class="file-preview-area">
      <div v-for="(f, idx) in pendingFiles" :key="idx" class="file-preview-item" :class="{ uploading: f.uploading }">
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/></svg>
        <span class="file-preview-name" :title="f.name">{{ f.name }}</span>
        <span class="file-preview-meta">{{ f.uploading ? t('input.parsing') : t('input.chars', { n: f.chars }) }}</span>
        <button class="file-preview-remove" @click="removeFile(idx)" :title="t('input.delete')">
          <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
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
          :placeholder="t('input.placeholder')"
          rows="1"
          @input="autoResize"
          @keydown="handleKeydown"
          @paste="handlePaste"
        ></textarea>

        <!-- 输入框内工具栏 -->
        <div class="input-inner-toolbar">
          <div v-if="supportsThinking" class="think-icon-btn" :class="{ active: deepThinking }" @click="deepThinking = !deepThinking" :title="t('input.deepThinking')">
            <img :src="thinkIconSrc" style="width:14px;height:14px;" />
          </div>
          <span v-if="supportsThinking" class="toolbar-divider"></span>
          <div v-if="modelsStore.webSearchEnabled" class="think-icon-btn" :class="{ active: webSearch }" @click="webSearch = !webSearch" :title="webSearch ? t('input.webSearchOn') : t('input.webSearchOff')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
          </div>
          <span v-if="modelsStore.webSearchEnabled" class="toolbar-divider"></span>
          <el-dropdown v-if="allRolePresets.length" trigger="click" placement="top-start" @command="selectRolePreset">
            <div class="think-icon-btn" :class="{ active: !!boundPresetId }" :title="boundPresetTitle ? t('input.roleBoundTitle', { title: boundPresetTitle }) : t('input.rolePickTitle')">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><rect x="4" y="8" width="16" height="12" rx="2"/><path d="M12 8V5"/><circle cx="12" cy="4" r="1"/><circle cx="9" cy="13" r="1"/><circle cx="15" cy="13" r="1"/><path d="M9 17h6"/></svg>
            </div>
            <template #dropdown>
              <el-dropdown-menu class="role-preset-menu">
                <el-dropdown-item command="" :disabled="!boundPresetId">{{ t('input.roleDefault') }}</el-dropdown-item>
                <template v-if="builtinAgents.length">
                  <li class="role-preset-group-title">{{ t('input.builtinAgents') }}</li>
                  <el-dropdown-item v-for="p in builtinAgents" :key="p.id" :command="p.id" :disabled="p.id === boundPresetId">{{ p.title }}</el-dropdown-item>
                </template>
                <template v-if="rolePresets.length">
                  <li class="role-preset-group-title">{{ t('input.myPrompts') }}</li>
                  <el-dropdown-item v-for="p in rolePresets" :key="p.id" :command="p.id" :disabled="p.id === boundPresetId">{{ p.title }}</el-dropdown-item>
                </template>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <span v-if="allRolePresets.length" class="toolbar-divider"></span>
          <!-- 附件按钮：图片与文本文档统一入口，按文件类型自动分流 -->
          <div class="upload-image-btn" :title="t('input.uploadAttach')" @click="triggerAttachUpload">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><path d="m21.44 11.05-9.19 9.19a6 6 0 0 1-8.49-8.49l8.57-8.57A4 4 0 1 1 18 8.84l-8.59 8.57a2 2 0 0 1-2.83-2.83l8.49-8.48"/></svg>
          </div>
          <div class="upload-image-btn" :title="t('input.clearContext')" @click="emit('clear-context')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="m13 11 9-9"/><path d="M14.6 12.6c.8.8.9 2.1.2 3L10 22l-8-8 6.4-4.8c.9-.7 2.2-.6 3 .2Z"/><path d="m6.8 10.4 6.8 6.8"/><path d="m5 17 1.4-1.4"/></svg>
          </div>
          <input type="file" ref="attachInputRef" :accept="ATTACH_ACCEPT" multiple style="display:none" @change="handleAttachUpload">
          <div class="model-select-area">
            <img v-if="currentIconIsImg" :src="currentIcon" class="model-area-icon" />
            <span v-else-if="currentIcon" class="model-area-icon model-area-emoji">{{ currentIcon }}</span>
            <el-cascader
              v-if="!isMobile"
              v-model="cascaderValue"
              :options="cascaderOptions"
              :props="cascaderProps"
              :show-all-levels="false"
              :style="{ width: modelInputWidth }"
              filterable
              placement="top"
              popper-class="model-cascader-popper"
              @change="handleModelChange"
            >
              <template #default="{ data }">
                <img v-if="data.icon && data.icon.startsWith('/')" :src="data.icon" class="cascader-node-icon" />
                <span v-else-if="data.icon" class="cascader-node-icon cascader-node-emoji">{{ data.icon }}</span>
                <span class="cascader-node-label">{{ data.label }}</span>
              </template>
            </el-cascader>
            <!-- 窄屏：单列下拉按厂商分组展示，避免级联两列在手机上过窄 -->
            <el-select
              v-else
              v-model="mobileModelId"
              :style="{ width: modelInputWidth }"
              placement="top"
              popper-class="model-select-popper-mobile"
              @change="handleMobileModelChange"
              @visible-change="onCascaderVisibleChange"
            >
              <el-option-group v-for="g in cascaderOptions" :key="g.value" :label="g.label">
                <el-option v-for="m in g.children" :key="m.value" :value="m.value" :label="m.label">
                  <img v-if="m.icon && m.icon.startsWith('/')" :src="m.icon" class="cascader-node-icon" />
                  <span v-else-if="m.icon" class="cascader-node-icon cascader-node-emoji">{{ m.icon }}</span>
                  <span class="cascader-node-label">{{ m.label }}</span>
                </el-option>
              </el-option-group>
            </el-select>
          </div>
          <button class="send-btn" :class="{ stop: isStreaming }" @click="handleSendClick" :title="isStreaming ? t('input.stop') : t('input.send')">
            <svg v-if="!isStreaming" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="22" y1="2" x2="11" y2="13"/><polygon points="22 2 15 22 11 13 2 9 22 2"/></svg>
            <svg v-else width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>
          </button>
        </div>
      </div>
    </div>
    <div class="input-footer">
      <span class="foot-l">{{ t('input.footShortcut') }}</span>
      <span class="foot-c">{{ t('input.footDisclaimer') }}</span>
      <span class="foot-r">v{{ APP_VERSION }}</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import { useModelsStore } from '@/stores/models'
import { useChatStore } from '@/stores/chat'
import { useTheme } from '@/composables/useTheme'
import { ElMessage, ElNotification } from 'element-plus'
import { APP_VERSION } from '@/config/version'
import { uploadChatImage, uploadChatDocument } from '@/api/chat'
import { getPromptPresets } from '@/api/user'

const props = defineProps({
  isStreaming: { type: Boolean, default: false },
  supportsThinking: { type: Boolean, default: false },
  supportsMultimodal: { type: Boolean, default: false }
})

const emit = defineEmits(['send', 'stop', 'clear-context'])

const { t } = useI18n()

const modelsStore = useModelsStore()
const chatStore = useChatStore()
const { getTheme } = useTheme()

const inputText = ref('')
const deepThinking = ref(false)
const webSearch = ref(false)
const pendingImages = ref([])
const pendingFiles = ref([]) // 待发送附件文档：{ name, url, chars, uploading }
const pasteWarning = ref('')
const textareaRef = ref(null)
const attachInputRef = ref(null)

const MAX_IMAGE_SIZE = 5 * 1024 * 1024
const MAX_DOC_SIZE = 3 * 1024 * 1024
// 支持的文本类文档类型（与后端 DocumentParseService 白名单一致），暂不支持 pdf
const DOC_EXTS = ['txt', 'log', 'md', 'markdown', 'csv', 'json', 'xml', 'yml', 'yaml', 'properties', 'doc', 'docx', 'xls', 'xlsx']
const DOC_ACCEPT = DOC_EXTS.map(ext => '.' + ext).join(',')
// 附件选择器同时接受图片与文本文档，选中后按类型分流处理
const ATTACH_ACCEPT = 'image/*,' + DOC_ACCEPT

function extOf(filename) {
  const idx = (filename || '').lastIndexOf('.')
  return idx >= 0 ? filename.slice(idx + 1).toLowerCase() : ''
}

// 移动端判定：窄屏(≤768px)改用单列分组下拉(el-select)选模型，
// 同时避免级联选择器搜索 input 获焦唤起手机软键盘
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
    container.style.paddingRight = ''
  }
  document.body.style.touchAction = ''
})

const thinkIconSrc = computed(() => {
  return getTheme() === 'dark' ? '/icons/icon_深度思考_ss.svg' : '/icons/icon_深度思考.svg'
})

// ===== 智能体/角色（提示词预设按会话绑定）=====
const rolePresets = ref([])
const builtinAgents = ref([])
onMounted(async () => {
  try {
    const data = await getPromptPresets()
    if (data && data.success) {
      rolePresets.value = data.presets || []
      builtinAgents.value = data.builtinAgents || []
    }
  } catch (e) { /* 未加载到预设时隐藏角色入口即可 */ }
})

// 内置智能体 + 用户自定义预设的合并列表（用于入口显隐与标题查找）
const allRolePresets = computed(() => [...builtinAgents.value, ...rolePresets.value])

// 当前会话绑定的预设 ID（存于 chatMeta，随会话历史同步）
const boundPresetId = computed(() => {
  const meta = chatStore.chatMeta[chatStore.currentChatId] || {}
  return meta.promptPresetId || ''
})
const boundPresetTitle = computed(() => {
  const p = allRolePresets.value.find(p => p.id === boundPresetId.value)
  return p ? p.title : ''
})

function selectRolePreset(presetId) {
  chatStore.setChatPromptPreset(chatStore.currentChatId, presetId)
  if (presetId) {
    const p = allRolePresets.value.find(p => p.id === presetId)
    ElMessage.success(t('input.roleBound', { title: p ? p.title : '' }))
  } else {
    ElMessage.success(t('input.roleReset'))
  }
}

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
  const name = String(modelsStore.currentModelName || t('input.selectModel'))
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
    notifyStreamingBlocked()
    syncCascaderFromStore()
    return
  }
  const modelId = val[1]
  applyModelSelect(modelId)
}

// 窄屏单列下拉的选中值（模型 id），与 store 保持同步
const mobileModelId = ref('')
watch(() => modelsStore.currentModelId, id => { mobileModelId.value = id || '' }, { immediate: true })

function handleMobileModelChange(modelId) {
  if (props.isStreaming) {
    notifyStreamingBlocked()
    mobileModelId.value = modelsStore.currentModelId || ''
    return
  }
  applyModelSelect(modelId)
}

// 右上角通知比顶部居中的 ElMessage 更醒目，避免被对话内容掩盖忽略
function notifyStreamingBlocked() {
  ElNotification.warning({
    title: t('input.streamBlockTitle'),
    message: t('input.streamBlockMsg'),
    position: 'top-right',
    duration: 3000
  })
}

function applyModelSelect(modelId) {
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
      // overflow:hidden 会使经典滚动条消失、内容区变宽导致文字回流（每行多排一字），
      // 锁定前测量滚动条实占宽度，用等宽 padding-right 补偿保持行宽不变
      const cs = getComputedStyle(container)
      const scrollbarW = container.offsetWidth - container.clientWidth
        - (parseFloat(cs.borderLeftWidth) || 0) - (parseFloat(cs.borderRightWidth) || 0)
      if (scrollbarW > 0) {
        container.style.paddingRight = ((parseFloat(cs.paddingRight) || 0) + scrollbarW) + 'px'
      }
      container.style.overflow = 'hidden'
      container.style.touchAction = 'none'
    }
    document.body.style.touchAction = 'none'
  } else {
    if (container) {
      container.style.overflow = ''
      container.style.touchAction = ''
      container.style.paddingRight = ''
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
  const hasFiles = pendingFiles.value.length > 0
  if (hasImages && !props.supportsMultimodal) {
    ElMessage.warning(t('input.noMultimodal'))
    return
  }
  if (pendingFiles.value.some(f => f.uploading)) {
    ElMessage.warning(t('input.attachParsing'))
    return
  }
  if (!text && !hasImages && !hasFiles) return
  emit('send', {
    text,
    images: pendingImages.value.slice(),
    attachments: pendingFiles.value.map(f => ({ name: f.name, url: f.url })),
    deepThinking: deepThinking.value,
    webSearch: webSearch.value
  })
  inputText.value = ''
  pendingImages.value = []
  pendingFiles.value = []
  pasteWarning.value = ''
  if (textareaRef.value) {
    textareaRef.value.style.height = 'auto'
  }
}

function handlePaste(e) {
  const items = e.clipboardData?.items
  if (!items) return
  // 粘贴内容分流：图片走图片上传，白名单文档走附件解析，其余文件类型直接拦截不上传
  const imageFiles = []
  const docFiles = []
  const unsupported = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (item.kind !== 'file') continue
    const file = item.getAsFile()
    if (!file) continue
    if (item.type.includes('image')) {
      imageFiles.push(file)
    } else if (DOC_EXTS.includes(extOf(file.name))) {
      docFiles.push(file)
    } else {
      unsupported.push(file.name || t('common.unknown'))
    }
  }
  // 未粘贴任何文件（纯文本粘贴）时不拦截默认行为
  if (imageFiles.length === 0 && docFiles.length === 0 && unsupported.length === 0) return
  e.preventDefault()
  if (unsupported.length > 0) {
    ElMessage.warning(t('input.unsupportedAttach', { names: unsupported.join('、') }))
  }
  imageFiles.forEach(file => addImageFile(file))
  docFiles.forEach(file => addDocFile(file))
}

// 统一附件入口：图片走图片上传，白名单文档走附件解析，其余类型提示不支持
function triggerAttachUpload() {
  if (attachInputRef.value) {
    attachInputRef.value.value = ''
    attachInputRef.value.click()
  }
}

function handleAttachUpload(e) {
  const files = e.target.files
  if (!files || files.length === 0) return
  const unsupported = []
  Array.from(files).forEach(file => {
    if (file.type.includes('image')) {
      addImageFile(file)
    } else if (DOC_EXTS.includes(extOf(file.name))) {
      addDocFile(file)
    } else {
      unsupported.push(file.name || t('common.unknown'))
    }
  })
  if (unsupported.length > 0) {
    ElMessage.warning(t('input.unsupportedAttach', { names: unsupported.join('、') }))
  }
}

// 上传图片到服务端，成功后以文件 URL 加入待发送列表（替代原 base64 内嵌）
async function addImageFile(file) {
  // 先尝试前端压缩，降低上传体积与模型多模态 token 消耗
  const compressed = await compressImage(file)
  if (compressed.size > MAX_IMAGE_SIZE) {
    ElMessage.warning(t('input.imageTooLarge'))
    return
  }
  try {
    const res = await uploadChatImage(compressed)
    if (res && res.success) {
      if (!props.supportsMultimodal) {
        pasteWarning.value = t('input.noMultimodalWarn')
      }
      pendingImages.value.push(res.url)
    } else {
      ElMessage.error((res && res.message) || t('input.imageUploadFailed'))
    }
  } catch (e) {
    // 错误提示已由 request 拦截器统一处理
  }
}

// 压缩阈值：小于 300KB 的图直接上传，长边超过 2048px 的图等比缩小
const COMPRESS_SIZE_THRESHOLD = 300 * 1024
const COMPRESS_MAX_EDGE = 2048

// canvas 压缩图片：转 JPEG(0.85) 并限制长边；GIF/SVG 不压缩（保动图/矢量），
// 压缩失败或压后反而变大时回退原图
async function compressImage(file) {
  if (file.size <= COMPRESS_SIZE_THRESHOLD) return file
  if (/gif|svg/i.test(file.type)) return file
  try {
    const bitmap = await createImageBitmap(file)
    const scale = Math.min(1, COMPRESS_MAX_EDGE / Math.max(bitmap.width, bitmap.height))
    const w = Math.max(1, Math.round(bitmap.width * scale))
    const h = Math.max(1, Math.round(bitmap.height * scale))
    const canvas = document.createElement('canvas')
    canvas.width = w
    canvas.height = h
    const ctx = canvas.getContext('2d')
    // JPEG 无透明通道，先铺白底避免 PNG 透明区域变黑
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, w, h)
    ctx.drawImage(bitmap, 0, 0, w, h)
    bitmap.close()
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.85))
    if (!blob || blob.size >= file.size) return file
    const newName = file.name.replace(/\.[^.]+$/, '') + '.jpg'
    return new File([blob], newName, { type: 'image/jpeg' })
  } catch (e) {
    return file
  }
}

function removeImage(idx) {
  pendingImages.value.splice(idx, 1)
  if (pendingImages.value.length === 0) pasteWarning.value = ''
}

// ===== 附件文档上传（服务端解析为纯文本，不依赖模型多模态） =====
// 上传并解析附件：先占位展示“解析中”，成功后回填引用 URL 与字数，失败则移除占位
async function addDocFile(file) {
  if (file.size > MAX_DOC_SIZE) {
    ElMessage.warning(t('input.docTooLarge', { name: file.name }))
    return
  }
  const item = { name: file.name, url: '', chars: 0, uploading: true }
  pendingFiles.value.push(item)
  try {
    const res = await uploadChatDocument(file)
    if (res && res.success) {
      item.url = res.url
      item.chars = res.chars || 0
      item.uploading = false
    } else {
      pendingFiles.value.splice(pendingFiles.value.indexOf(item), 1)
      ElMessage.error((res && res.message) || t('input.docUploadFailed'))
    }
  } catch (e) {
    pendingFiles.value.splice(pendingFiles.value.indexOf(item), 1)
    // 错误提示已由 request 拦截器统一处理
  }
}

function removeFile(idx) {
  pendingFiles.value.splice(idx, 1)
}
</script>
