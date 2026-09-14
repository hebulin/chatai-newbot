<template>
  <section class="admin-card output-settings" v-loading="loading" ref="root" :aria-label="title">
    <h3>{{ title }}</h3>
    <p class="output-hint">{{ intro }}</p>
    <p v-if="loadError" role="alert" class="output-error">{{ loadError }} <el-button text @click="load">{{ adminText('重试') }}</el-button></p>
    <el-tabs v-model="activeTab" class="output-tabs">
      <el-tab-pane :label="adminText('全局配置')" name="global">
        <div class="output-global">
          <label v-if="!isContext" class="output-field">
            <span>{{ adminText('输出模式') }}</span>
            <select v-model="globalMode" :aria-label="adminText('全局输出模式')" :disabled="globalSaving || !ready">
              <option value="limited">{{ adminText('指定上限') }}</option>
              <option value="unlimited">{{ adminText('不限制') }}</option>
            </select>
          </label>
          <label v-if="globalMode === 'limited'" class="output-field">
            <span>{{ fieldLabel }}</span>
            <input v-model.number="globalDraft" type="number" min="1" max="2147483647" step="1" :disabled="globalSaving || !ready" :aria-label="globalLabel" :aria-describedby="`${props.kind}-global-hint ${props.kind}-global-error`" :aria-invalid="!!globalError" />
          </label>
          <p :id="`${props.kind}-global-hint`" class="output-hint">{{ adminText('全局缺省值为 {default} Token；当前生效：{value}', { default: defaultLimit, value: formatLimit(globalCurrent) }) }}</p>
          <p :id="`${props.kind}-global-error`" v-if="globalError" class="output-error" role="alert">{{ globalError }}</p>
          <el-button type="primary" :loading="globalSaving" :disabled="!ready" @click="saveGlobal">{{ adminText('保存全局配置') }}</el-button>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="adminText('单个模型配置')" name="models">
        <div class="output-toolbar">
          <p class="output-hint">{{ adminText('只显示已添加的配置；删除配置后，该模型恢复使用全局设置。') }}</p>
          <el-button type="primary" :disabled="!ready || !canAdd" @click="addRow">{{ adminText('新增') }}</el-button>
        </div>
        <div v-if="!rows.length && ready" class="output-empty">
          <strong>{{ adminText('暂无单个模型配置') }}</strong>
          <p>{{ adminText('点击新增，选择模型并填写 Token 上限。未配置的模型自动使用全局设置。') }}</p>
          <p v-if="!availableModels.length">{{ adminText('请先在模型管理中添加可用模型') }}</p>
        </div>
        <div v-else class="output-rows">
          <div v-for="(row, index) in rows" :key="row.key" class="output-row" :data-output-row="index" :data-model-id="row.savedId || ''">
            <div class="output-row-fields">
              <label class="output-field output-model-field">
                <span>{{ adminText('模型') }}</span>
                <select v-model="row.modelId" :disabled="row.saving || !!row.savedId" :aria-label="adminText('配置 {index} 的模型', { index: index + 1 })">
                  <option disabled value="">{{ adminText('请选择模型') }}</option>
                  <option v-for="model in availableModels" :key="model.id" :value="model.id" :disabled="isSelectedElsewhere(model.id, row)">{{ model.name }}{{ model.providerName ? ` · ${model.providerName}` : '' }}</option>
                </select>
              </label>
              <label class="output-field output-limit-field">
                <span>{{ fieldLabel }}</span>
                <input v-model.number="row.limit" type="number" :min="isContext ? 1 : 0" max="2147483647" step="1" :disabled="row.saving" :aria-label="adminText('配置 {index} 的 Token 上限', { index: index + 1 })" :aria-describedby="`${props.kind}-row-hint-${row.key} ${props.kind}-row-error-${row.key}`" :aria-invalid="!!row.error" />
              </label>
              <div class="output-row-actions">
                <el-button type="primary" :loading="row.saving" @click="saveRow(row)">{{ adminText('保存') }}</el-button>
                <el-button :disabled="row.saving" @click="removeRow(row)">{{ adminText(row.savedId ? '删除' : '取消') }}</el-button>
              </div>
            </div>
            <p :id="`${props.kind}-row-hint-${row.key}`" class="output-hint">{{ rowHint }}<span v-if="row.savedId"> {{ adminText('当前生效：{value}', { value: formatLimit(row.savedLimit) }) }}</span></p>
            <p :id="`${props.kind}-row-error-${row.key}`" v-if="row.error" class="output-error" role="alert">{{ row.error }}</p>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>
    <p class="output-hint output-provider-note">{{ providerHint }}</p>
  </section>
</template>

<script setup>
import { computed, nextTick, onActivated, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminText } from '@/i18n'
import { getChatOutputSettings, setChatOutputGlobal, setChatOutputModel, getChatContextSettings, setChatContextGlobal, setChatContextModel } from '@/api/settings'

// 两种配置通过独立实例加载，切换分类不会覆盖另一种配置或未保存的草稿。
const props = defineProps({ kind: { type: String, default: 'output' } })
const isContext = props.kind === 'context'
const defaultLimit = isContext ? 32000 : 16384
const field = isContext ? 'contextWindow' : 'maxOutputTokens'
const globalField = isContext ? 'globalContextWindow' : 'globalMaxOutputTokens'
const getSettings = isContext ? getChatContextSettings : getChatOutputSettings
const setGlobal = isContext ? setChatContextGlobal : setChatOutputGlobal
const setModel = isContext ? setChatContextModel : setChatOutputModel
const root = ref(null)
const title = computed(() => isContext ? adminText('上下文大小') : adminText('输出大小'))
const fieldLabel = computed(() => isContext ? adminText('上下文大小（Token）') : adminText('最大输出 Token'))
const globalLabel = computed(() => isContext ? adminText('全局上下文大小') : adminText('全局输出上限'))
const intro = computed(() => isContext
  ? adminText('上下文大小是输入、历史与输出的总容量，请按模型实际能力配置。未单独配置的模型继承全局设置。')
  : adminText('输出大小是单次回答的上限，包含思考内容。未单独配置的模型继承全局设置。'))
const rowHint = computed(() => isContext
  ? adminText('请输入正整数。删除配置后恢复全局设置；留空不会自动填入默认值。')
  : adminText('0 表示不限制；输入框留空不会自动填入默认值。'))
const providerHint = computed(() => isContext
  ? adminText('系统会为输出保留空间，超出预算的早期历史按完整轮次裁剪。当前输入超出容量时会明确提示。')
  : adminText('不限制表示不额外设置输出上限，仍受上下文剩余空间及厂商限制影响。上下文不足时实际输出可能小于此值。'))
const loading = ref(false)
const ready = ref(false)
const loadError = ref('')
const globalError = ref('')
const availableModels = ref([])
const rows = ref([])
const activeTab = ref('global')
const globalMode = ref('limited')
const globalDraft = ref(defaultLimit)
const globalCurrent = ref(defaultLimit)
const globalSaving = ref(false)
let nextRowId = 0
const canAdd = computed(() => rows.value.length < availableModels.value.length)

/** 将已生效预算转换为显示文本，0 始终表示不限。 */
function formatLimit(limit) {
  return limit === 0 ? adminText('不限制') : `${limit} Token`
}

/** 创建一条本地编辑行，新行不预选模型、不填默认 Token。 */
function makeRow(model = null) {
  return { key: ++nextRowId, modelId: model?.id || '', savedId: model?.id || '',
    limit: model?.[field] ?? '', savedLimit: model?.[field] ?? null, saving: false, error: '' }
}

/** 下拉候选保留全量模型，配置列表只从已保存的非空覆盖生成。 */
async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await getSettings()
    if (!result?.success) throw new Error()
    globalCurrent.value = result.data[globalField]
    globalMode.value = globalCurrent.value === 0 ? 'unlimited' : 'limited'
    globalDraft.value = globalCurrent.value || defaultLimit
    availableModels.value = result.data.availableModels || result.data.models || []
    rows.value = result.data.models.filter(model => model[field] != null).map(makeRow)
    ready.value = true
  } catch {
    loadError.value = adminText('配置加载失败，请重试')
  } finally { loading.value = false }
}

/** 检查其他行占用的模型，禁止重复新增覆盖或同时保存同一模型。 */
function isSelectedElsewhere(id, current) {
  return rows.value.some(row => row !== current && row.modelId === id)
}

/** 新增空白编辑行，将键盘焦点移到新行的模型选择器。 */
async function addRow() {
  if (!canAdd.value) return
  rows.value.push(makeRow())
  await nextTick()
  root.value?.querySelector(`[data-output-row="${rows.value.length - 1}"] select`)?.focus()
}

/** 验证整数输入；空白保持空白并提示，绝不隐式转成缺省值或不限。 */
function validateLimit(value, allowZero = true) {
  if (!Number.isInteger(value) || value < (allowZero ? 0 : 1) || value > 2147483647) {
    throw new Error(adminText(allowZero ? '请输入 0 至 2147483647 的整数' : '请输入 1 至 2147483647 的整数'))
  }
  return value
}

/** 保存全局配置，不覆盖单个模型尚未保存的草稿。 */
async function saveGlobal() {
  globalSaving.value = true
  globalError.value = ''
  try {
    const limit = !isContext && globalMode.value === 'unlimited' ? 0 : validateLimit(globalDraft.value, false)
    const result = await setGlobal(limit)
    if (!result?.success) throw new Error(adminText('保存失败'))
    globalCurrent.value = limit
    ElMessage.success(adminText('保存成功'))
  } catch (failure) { globalError.value = failure.message || adminText('保存失败') }
  finally { globalSaving.value = false }
}

/** 保存指定模型的独立配置，成功前不将本地草稿视为已生效。 */
async function saveRow(row) {
  row.error = ''
  try {
    if (!row.modelId) throw new Error(adminText('请选择模型'))
    if (isSelectedElsewhere(row.modelId, row)) throw new Error(adminText('该模型已有配置，请勿重复添加'))
    const limit = validateLimit(row.limit, !isContext)
    row.saving = true
    const result = await setModel(row.modelId, limit)
    if (!result?.success) throw new Error(adminText('保存失败'))
    row.savedId = row.modelId
    row.savedLimit = limit
    ElMessage.success(adminText('保存成功'))
  } catch (failure) { row.error = failure.message || adminText('保存失败') }
  finally { row.saving = false }
}

/** 取消未保存行，或清除已保存覆盖以恢复全局继承。 */
async function removeRow(row) {
  row.saving = true
  row.error = ''
  try {
    if (row.savedId) {
      const result = await setModel(row.savedId, null)
      if (!result?.success) throw new Error(adminText('保存失败'))
    }
    rows.value = rows.value.filter(item => item !== row)
  } catch (failure) { row.error = failure.message || adminText('保存失败') }
  finally { row.saving = false }
}

onMounted(load)
let firstActivation = true
onActivated(() => {
  if (firstActivation) { firstActivation = false; return }
  load()
})
</script>

<style scoped>
.output-settings h3{font-size:16px;color:var(--ink);margin:0 0 8px;text-wrap:balance}.output-hint{font-size:12px;line-height:1.7;color:var(--ink-3);margin:8px 0;text-wrap:pretty}.output-error{color:var(--danger);font-size:13px;margin:8px 0}.output-global{max-width:480px;padding:8px 0}.output-field{display:flex;flex-direction:column;gap:8px;margin-bottom:16px;min-width:0}.output-field>span{font-size:13px;color:var(--ink);font-weight:500}.output-field select,.output-field input{box-sizing:border-box;width:100%;background:var(--paper-2);color:var(--ink);border:1px solid var(--input-border);border-radius:var(--radius-sm);padding:8px 11px;font:inherit;font-size:13px;min-height:32px;min-width:0;transition:border-color 0.15s ease,box-shadow 0.15s ease}.output-field select:hover,.output-field input:hover{border-color:var(--primary)}.output-field input{font-variant-numeric:tabular-nums}.output-field select:focus-visible,.output-field input:focus-visible{outline:none;border-color:var(--primary);box-shadow:0 0 0 2px var(--accent-soft)}.output-field select:disabled,.output-field input:disabled{background:var(--paper-3);color:var(--ink-4);cursor:not-allowed}.output-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.output-toolbar .el-button{flex-shrink:0}.output-empty{border:1px dashed var(--line);border-radius:var(--radius);padding:36px 24px;text-align:center;background:var(--paper-2)}.output-empty strong{font-size:14px;color:var(--ink);font-weight:500}.output-empty p{font-size:13px;line-height:1.7;color:var(--ink-3);margin:8px 0 0}.output-row{padding:16px 0;border-top:1px solid var(--line)}.output-row-fields{display:flex;gap:16px;align-items:flex-end}.output-row-fields .output-field{margin:0}.output-model-field{flex:1}.output-limit-field{flex:0 1 180px}.output-row-actions{display:flex;gap:8px;flex-shrink:0;align-items:center;min-height:32px}.output-row-actions .el-button+.el-button{margin-left:0}.output-provider-note{border-top:1px solid var(--line);margin-top:24px;padding-top:16px}.output-tabs{margin-top:16px}@media(max-width:768px){.output-row-fields{flex-direction:column;align-items:stretch;gap:12px}.output-limit-field{flex:auto}.output-field input,.output-field select{font-size:16px}.output-row-actions{justify-content:flex-end}.output-toolbar{align-items:flex-start}.output-empty{padding:28px 16px}}
</style>
