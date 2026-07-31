<template>
  <Teleport to="body">
    <div class="modal-overlay" @click="$emit('close')">
      <div class="us-modal-container" @click.stop>
        <div class="modal-header">
          <div class="us-header-top">
            <span class="us-eyebrow"><b>ANALYTICS</b> · {{ t('usage.eyebrow') }}</span>
            <span class="us-scope-badge" :class="isAdmin ? 'us-scope-admin' : 'us-scope-self'">
              {{ isAdmin ? t('usage.scopeAdmin') : t('usage.scopeSelf') }}
            </span>
          </div>
          <button class="modal-close" @click="$emit('close')">✕</button>
        </div>

        <!-- 子Tab -->
        <div class="us-body">
          <div class="us-subtab-nav">
            <div class="us-subtab-item" :class="{ active: subTab === 'usage' }" @click="subTab = 'usage'">{{ t('usage.tabUsage') }}</div>
            <div class="us-subtab-item" :class="{ active: subTab === 'stats' }" @click="switchToStats">{{ t('usage.tabStats') }}</div>
          </div>

          <!-- 使用记录 -->
          <div v-show="subTab === 'usage'" class="us-sub-content">
            <div class="us-search-bar">
              <div v-if="isAdmin" class="us-field">
                <label class="us-label">{{ t('usage.user') }}</label>
                <el-select v-model="usageFilter.username" class="us-el-select" :placeholder="t('usage.allUsers')" clearable filterable size="small">
                  <el-option v-for="u in usernames" :key="u" :label="u" :value="u" />
                </el-select>
              </div>
              <div class="us-field">
                <label class="us-label">{{ t('usage.model') }}</label>
                <el-select v-model="usageFilter.modelName" class="us-el-select us-el-select-wide" :placeholder="t('usage.allModels')" clearable filterable size="small">
                  <el-option v-for="m in modelNames" :key="m" :label="m" :value="m" />
                </el-select>
              </div>
              <div class="us-field">
                <label class="us-label">{{ t('usage.dateRange') }}</label>
                <el-date-picker
                  v-model="usageDateRange"
                  type="daterange"
                  :range-separator="t('usage.to')"
                  :start-placeholder="t('usage.startDate')"
                  :end-placeholder="t('usage.endDate')"
                  value-format="YYYY-MM-DD"
                  :shortcuts="dateShortcuts"
                  class="us-date-range"
                  popper-class="us-date-popper"
                  size="small"
                />
              </div>
              <div class="us-actions">
                <button class="us-btn us-btn-primary" @click="searchUsage">{{ t('common.search') }}</button>
                <button class="us-btn" @click="resetUsageFilter">{{ t('common.reset') }}</button>
              </div>
            </div>
            <!-- 使用记录列表：与后台管理统一使用 el-table（自适应列宽 + 拖拽调宽 + 横向滚动） -->
            <el-table :data="usageLogs" class="us-el-table" stripe border :empty-text="t('usage.noData')" style="width: 100%">
              <el-table-column prop="timestamp" :label="t('usage.time')" :width="usageColW.timestamp" show-overflow-tooltip />
              <el-table-column v-if="isAdmin" prop="username" :label="t('usage.user')" :width="usageColW.username" show-overflow-tooltip />
              <el-table-column prop="modelName" :label="t('usage.model')" :width="usageColW.modelName" show-overflow-tooltip />
              <el-table-column :label="t('usage.promptTokens')" :width="usageColW.prompt">
                <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
              </el-table-column>
              <el-table-column :label="t('usage.completionTokens')" :width="usageColW.completion">
                <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
              </el-table-column>
              <el-table-column :label="t('usage.reasoningTokens')" :width="usageColW.reasoning">
                <template #default="{ row }">{{ fmt(row.reasoningTokens) }}</template>
              </el-table-column>
              <el-table-column :label="t('usage.cachedTokens')" :width="usageColW.cached">
                <template #default="{ row }">{{ fmt(row.cachedTokens) }}</template>
              </el-table-column>
              <el-table-column :label="t('usage.thinkingMode')" :width="usageColW.thinking">
                <template #default="{ row }">
                  <span class="us-thinking-badge" :class="{ on: row.deepThinking }">{{ row.deepThinking ? t('usage.deep') : t('usage.standard') }}</span>
                </template>
              </el-table-column>
            </el-table>
            <div class="us-pagination">
              <select v-model.number="usageSize" class="us-page-size" @change="usagePage = 1; loadUsageLogs()">
                <option :value="10">{{ t('common.perPage', { n: 10 }) }}</option>
                <option :value="20">{{ t('common.perPage', { n: 20 }) }}</option>
                <option :value="50">{{ t('common.perPage', { n: 50 }) }}</option>
              </select>
              <button class="us-btn" :disabled="usagePage <= 1" @click="usagePage--; loadUsageLogs()">{{ t('common.prevPage') }}</button>
              <span class="us-page-info">{{ t('common.pageInfo', { page: usagePage, totalPages: usageTotalPages, total: usageTotal }) }}</span>
              <button class="us-btn" :disabled="usagePage >= usageTotalPages" @click="usagePage++; loadUsageLogs()">{{ t('common.nextPage') }}</button>
            </div>
          </div>

          <!-- 用户统计 -->
          <div v-show="subTab === 'stats'" class="us-sub-content">
            <div class="us-search-bar">
              <div v-if="isAdmin" class="us-field">
                <label class="us-label">{{ t('usage.userMulti') }}</label>
                <el-select
                  v-model="statsFilter.usernames"
                  class="us-el-select us-el-select-multi"
                  :placeholder="t('usage.allUsers')"
                  multiple
                  clearable
                  filterable
                  collapse-tags
                  collapse-tags-tooltip
                  :max-collapse-tags="2"
                  size="small"
                >
                  <el-option v-for="u in usernames" :key="u" :label="u" :value="u" />
                </el-select>
              </div>
              <div class="us-field">
                <label class="us-label">{{ t('usage.dateRange') }}</label>
                <el-date-picker
                  v-model="statsDateRange"
                  type="daterange"
                  :range-separator="t('usage.to')"
                  :start-placeholder="t('usage.startDate')"
                  :end-placeholder="t('usage.endDate')"
                  value-format="YYYY-MM-DD"
                  :shortcuts="dateShortcuts"
                  class="us-date-range"
                  popper-class="us-date-popper"
                  size="small"
                />
              </div>
              <div class="us-actions">
                <button class="us-btn us-btn-primary" @click="searchStats">{{ t('common.search') }}</button>
                <button class="us-btn" @click="resetStatsFilter">{{ t('common.reset') }}</button>
              </div>
            </div>

            <!-- 四维汇总卡片 -->
            <div class="us-stat-cards">
              <div class="us-stat-card" :class="{ tick: tickFlags.count }">
                <div class="us-stat-num">{{ statsSummary.count }}</div>
                <div class="us-stat-label">{{ t('usage.cardCalls') }}</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.promptTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.promptTokens) }}</div>
                <div class="us-stat-label">{{ t('usage.cardInput') }}</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.completionTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.completionTokens) }}</div>
                <div class="us-stat-label">{{ t('usage.cardOutput') }}</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.reasoningTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.reasoningTokens) }}</div>
                <div class="us-stat-label">{{ t('usage.cardReasoning') }}</div>
              </div>
            </div>

            <!-- 迷你子Tab：列表 / 曲线图 / 柱状图 -->
            <div class="us-mini-tab-nav">
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'list' }" @click="miniTab = 'list'">{{ t('usage.miniList') }}</div>
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'line' }" @click="miniTab = 'line'">{{ t('usage.miniLine') }}</div>
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'bar' }" @click="miniTab = 'bar'">{{ t('usage.miniBar') }}</div>
            </div>

            <!-- 列表 -->
            <div v-show="miniTab === 'list'">
              <!-- 统计结果列表：与后台管理统一使用 el-table（自适应列宽 + 拖拽调宽 + 横向滚动） -->
              <el-table :data="userStats" class="us-el-table" stripe border :empty-text="t('usage.statsEmptyText')" style="width: 100%">
                <el-table-column v-if="isAdmin" prop="username" :label="t('usage.user')" :width="statsColW.username" show-overflow-tooltip />
                <el-table-column prop="date" :label="t('usage.date')" :width="statsColW.date" show-overflow-tooltip />
                <el-table-column prop="modelName" :label="t('usage.model')" :width="statsColW.modelName" show-overflow-tooltip />
                <el-table-column prop="count" :label="t('usage.calls')" :width="statsColW.count" />
                <el-table-column :label="t('usage.promptTokens')" :width="statsColW.prompt">
                  <template #default="{ row }">{{ fmt(row.promptTokens) }}</template>
                </el-table-column>
                <el-table-column :label="t('usage.completionTokens')" :width="statsColW.completion">
                  <template #default="{ row }">{{ fmt(row.completionTokens) }}</template>
                </el-table-column>
                <el-table-column :label="t('usage.reasoningTokens')" :width="statsColW.reasoning">
                  <template #default="{ row }">{{ fmt(row.reasoningTokens) }}</template>
                </el-table-column>
                <el-table-column :label="t('usage.cachedTokens')" :width="statsColW.cached">
                  <template #default="{ row }">{{ fmt(row.cachedTokens) }}</template>
                </el-table-column>
                <el-table-column :label="t('usage.thinkingCount')" :width="statsColW.thinkingCount">
                  <template #default="{ row }">{{ row.thinkingCount || 0 }}</template>
                </el-table-column>
              </el-table>
              <div class="us-pagination">
                <select v-model.number="statsPageSize" class="us-page-size" @change="statsPage = 1; loadUserStats()">
                  <option :value="10">10条/页</option>
                  <option :value="20">20条/页</option>
                  <option :value="50">50条/页</option>
                </select>
                <button class="us-btn" :disabled="statsPage <= 1" @click="statsPage--; loadUserStats()">{{ t('common.prevPage') }}</button>
                <span class="us-page-info">{{ t('common.pageInfo', { page: statsPage, totalPages: statsTotalPages, total: statsTotal }) }}</span>
                <button class="us-btn" :disabled="statsPage >= statsTotalPages" @click="statsPage++; loadUserStats()">{{ t('common.nextPage') }}</button>
              </div>
            </div>

            <!-- 曲线图 -->
            <div v-show="miniTab === 'line'">
              <div class="us-chart-toolbar">
                <span class="us-metric-label">{{ t('usage.metric') }}</span>
                <div class="us-metric-selector">
                  <button v-for="m in lineMetrics" :key="m.key" class="us-metric-btn" :class="{ active: lineMetric === m.key }" @click="lineMetric = m.key">{{ m.label }}</button>
                </div>
              </div>
              <div class="us-chart-canvas" v-html="lineChartSvg"></div>
            </div>

            <!-- 柱状图 -->
            <div v-show="miniTab === 'bar'">
              <div class="us-chart-toolbar">
                <span class="us-metric-label">{{ t('usage.metric') }}</span>
                <div class="us-metric-selector">
                  <button v-for="m in barMetrics" :key="m.key" class="us-metric-btn" :class="{ active: barMetric === m.key }" @click="barMetric = m.key">{{ m.label }}</button>
                </div>
              </div>
              <div class="us-chart-canvas" v-html="barChartSvg"></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '@/stores/auth'
import { getUsageLogs, getUserStats, getUsernames } from '@/api/usage'
import { autoColWidth } from '@/composables/useTableAutoWidth'

defineEmits(['close'])

const { t } = useI18n()

const authStore = useAuthStore()
const isAdmin = ref(authStore.role === 'admin')

const subTab = ref('usage')
const miniTab = ref('list')
const statsLoaded = ref(false)
const usageLogs = ref([])
const userStats = ref([])
const chartData = ref([])
const usagePage = ref(1)
const usageSize = ref(10)
const usageTotal = ref(0)
const usageTotalPages = ref(1)
const statsPage = ref(1)
const statsPageSize = ref(10)
const statsTotal = ref(0)
const statsTotalPages = ref(1)

// 筛选下拉数据（来自 /usage/filters）
const usernames = ref([])
const modelNames = ref([])

const usageFilter = ref({ username: '', modelName: '' })
// 用户统计用户维度：多选数组（仅管理员可见），选多个时图表按用户对比展示
const statsFilter = ref({ usernames: [] })
// 最近一次搜索的用户维度快照（避免下拉未搜索就影响图表分组）
const chartUserDim = ref([])

// 日期范围（el-date-picker daterange 绑定数组）
function getLast30Days() {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - 29)
  const fmt = d => d.toISOString().slice(0, 10)
  return [fmt(start), fmt(end)]
}
const usageDateRange = ref(getLast30Days())
const statsDateRange = ref(getLast30Days())

// 日期快捷选项（文案随语言切换，改为 computed）
const dateShortcuts = computed(() => [
  { text: t('usage.today'), value: () => { const d = new Date(); return [d, d] } },
  { text: t('usage.thisWeek'), value: () => { const end = new Date(); const start = new Date(); const day = start.getDay() || 7; start.setDate(start.getDate() - day + 1); return [start, end] } },
  { text: t('usage.thisMonth'), value: () => { const end = new Date(); const start = new Date(end.getFullYear(), end.getMonth(), 1); return [start, end] } },
  { text: t('usage.last30Days'), value: () => { const end = new Date(); const start = new Date(); start.setDate(start.getDate() - 29); return [start, end] } }
])

// 图表指标（与旧版对齐：曲线 6 指标、柱状 6 指标，含缓存Token/思考模式；label 随语言切换，改为 computed）
const lineMetric = ref('count')
const barMetric = ref('totalTokens')
const lineMetrics = computed(() => [
  { key: 'count', label: t('usage.calls') },
  { key: 'promptTokens', label: t('usage.promptTokens') },
  { key: 'completionTokens', label: t('usage.completionTokens') },
  { key: 'reasoningTokens', label: t('usage.reasoningTokens') },
  { key: 'cachedTokens', label: t('usage.cachedTokens') },
  { key: 'thinkingCount', label: t('usage.thinkingMode') }
])
const barMetrics = computed(() => [
  { key: 'totalTokens', label: t('usage.totalTokens') },
  { key: 'count', label: t('usage.calls') },
  { key: 'promptTokens', label: t('usage.promptTokens') },
  { key: 'completionTokens', label: t('usage.completionTokens') },
  { key: 'reasoningTokens', label: t('usage.reasoningTokens') },
  { key: 'cachedTokens', label: t('usage.cachedTokens') }
])

// 汇总卡片数字刷新动效标记
const tickFlags = ref({ count: false, promptTokens: false, completionTokens: false, reasoningTokens: false })

function fmt(n) {
  if (n === undefined || n === null) return '-'
  return Number(n).toLocaleString('en-US')
}
function fmtShort(n) {
  if (n == null) return '0'
  if (n >= 1e6) return (n / 1e6).toFixed(1) + 'M'
  if (n >= 1e3) return (n / 1e3).toFixed(1) + 'K'
  return String(n)
}
function esc(v) {
  return String(v ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
}

// 移动端弹窗打开时锁定 body 滚动，防止日期选择器弹出后背景页面可滑动
let savedScrollY = 0
function lockBodyScroll() {
  savedScrollY = window.scrollY
  document.body.style.position = 'fixed'
  document.body.style.top = `-${savedScrollY}px`
  document.body.style.left = '0'
  document.body.style.right = '0'
  document.body.style.overflow = 'hidden'
}
function unlockBodyScroll() {
  document.body.style.position = ''
  document.body.style.top = ''
  document.body.style.left = ''
  document.body.style.right = ''
  document.body.style.overflow = ''
  window.scrollTo(0, savedScrollY)
}

// 打开弹窗即加载：筛选下拉 + 使用记录 + 全局汇总图表数据
onMounted(() => {
  lockBodyScroll()
  loadFilters()
  loadUsageLogs()
  loadCharts()
})

onBeforeUnmount(() => {
  unlockBodyScroll()
})

// 加载筛选下拉数据（usernames 仅 admin 用，modelNames 所有用户用）
async function loadFilters() {
  try {
    const data = await getUsernames()
    if (data && data.success) {
      usernames.value = data.usernames || []
      modelNames.value = data.modelNames || []
    }
  } catch (e) { /* ignore */ }
}

// 切换到用户统计tab时自动触发首次查询
function switchToStats() {
  subTab.value = 'stats'
  if (!statsLoaded.value) {
    statsLoaded.value = true
    searchStats()
  }
}

// 日期范围校验：≤31 天、结束不早于开始、格式合法；非法返回错误信息否则返回 null
function validateDateRange(dateRange) {
  if (dateRange && dateRange.length === 2) {
    const s = new Date(dateRange[0])
    const e = new Date(dateRange[1])
    if (isNaN(s.getTime()) || isNaN(e.getTime())) return t('usage.errDateFormat')
    if (s > e) return t('usage.errDateOrder')
    const diffDays = (e - s) / 86400000
    if (diffDays > 31) return t('usage.errDateSpan')
  }
  return null
}

// 汇总计数：直接从当前查询结果聚合，保证与列表/图表同口径
const statsSummary = computed(() => {
  const s = { count: 0, promptTokens: 0, completionTokens: 0, reasoningTokens: 0 }
  chartData.value.forEach(r => {
    s.count += r.count || 0
    s.promptTokens += r.promptTokens || 0
    s.completionTokens += r.completionTokens || 0
    s.reasoningTokens += r.reasoningTokens || 0
  })
  return s
})

// 汇总数字变化时触发卡片刷新动效
watch(statsSummary, (val) => {
  Object.keys(tickFlags.value).forEach(k => {
    tickFlags.value[k] = true
  })
  setTimeout(() => {
    Object.keys(tickFlags.value).forEach(k => { tickFlags.value[k] = false })
  }, 500)
}, { deep: true })

async function loadUsageLogs() {
  const err = validateDateRange(usageDateRange.value)
  if (err) { ElMessage.warning(err); return }
  try {
    const params = { page: usagePage.value, size: usageSize.value }
    if (usageFilter.value.username) params.username = usageFilter.value.username
    if (usageFilter.value.modelName) params.modelName = usageFilter.value.modelName
    if (usageDateRange.value && usageDateRange.value[0]) params.startDate = usageDateRange.value[0]
    if (usageDateRange.value && usageDateRange.value[1]) params.endDate = usageDateRange.value[1]
    const data = await getUsageLogs(params)
    if (data && data.success) {
      usageLogs.value = data.data || []
      usageTotal.value = data.total || 0
      usageTotalPages.value = data.totalPages || 1
    }
  } catch (e) { ElMessage.error(t('usage.loadUsageFailed')) }
}

// 使用记录搜索：重置页码后加载
function searchUsage() {
  usagePage.value = 1
  loadUsageLogs()
}

async function loadUserStats() {
  const err = validateDateRange(statsDateRange.value)
  if (err) { ElMessage.warning(err); return }
  try {
    const params = { page: statsPage.value, size: statsPageSize.value }
    if (statsFilter.value.usernames.length) params.usernames = statsFilter.value.usernames.join(',')
    if (statsDateRange.value && statsDateRange.value[0]) params.startDate = statsDateRange.value[0]
    if (statsDateRange.value && statsDateRange.value[1]) params.endDate = statsDateRange.value[1]
    const data = await getUserStats(params)
    if (data && data.success) {
      userStats.value = data.data || []
      statsTotal.value = data.total || 0
      statsTotalPages.value = data.totalPages || 1
    }
  } catch (e) { ElMessage.error(t('usage.loadStatsFailed')) }
}

async function loadCharts() {
  try {
    const params = { getAll: true, size: 10000 }
    if (statsFilter.value.usernames.length) params.usernames = statsFilter.value.usernames.join(',')
    if (statsDateRange.value && statsDateRange.value[0]) params.startDate = statsDateRange.value[0]
    if (statsDateRange.value && statsDateRange.value[1]) params.endDate = statsDateRange.value[1]
    const data = await getUserStats(params)
    if (data && data.success) {
      chartData.value = data.data || []
      // 记录本次搜索的用户维度，驱动图表多用户对比模式
      chartUserDim.value = [...statsFilter.value.usernames]
    }
  } catch (e) { ElMessage.error(t('usage.loadChartsFailed')) }
}

// 用户统计搜索：校验日期 + 重置页码 + 加载列表与图表
function searchStats() {
  const err = validateDateRange(statsDateRange.value)
  if (err) { ElMessage.warning(err); return }
  statsPage.value = 1
  loadUserStats()
  loadCharts()
}

function resetUsageFilter() {
  usageFilter.value = { username: '', modelName: '' }
  usageDateRange.value = getLast30Days()
  usagePage.value = 1
  loadUsageLogs()
}

function resetStatsFilter() {
  statsFilter.value = { usernames: [] }
  statsDateRange.value = getLast30Days()
  searchStats()
}

// 列宽自适应：与后台管理一致，按当前列最长内容计算（上限 50 汉字），配合 border 支持拖拽调宽
const usageColW = computed(() => {
  const list = usageLogs.value
  return {
    timestamp: autoColWidth(list.map(l => l.timestamp), { header: t('usage.time'), min: 110 }),
    username: autoColWidth(list.map(l => l.username), { header: t('usage.user'), min: 80 }),
    modelName: autoColWidth(list.map(l => l.modelName), { header: t('usage.model'), min: 100 }),
    prompt: autoColWidth(list.map(l => fmt(l.promptTokens)), { header: t('usage.promptTokens') }),
    completion: autoColWidth(list.map(l => fmt(l.completionTokens)), { header: t('usage.completionTokens') }),
    reasoning: autoColWidth(list.map(l => fmt(l.reasoningTokens)), { header: t('usage.reasoningTokens') }),
    cached: autoColWidth(list.map(l => fmt(l.cachedTokens)), { header: t('usage.cachedTokens') }),
    thinking: autoColWidth([t('usage.deep'), t('usage.standard')], { header: t('usage.thinkingMode'), extra: 12 })
  }
})
const statsColW = computed(() => {
  const list = userStats.value
  return {
    username: autoColWidth(list.map(s => s.username), { header: t('usage.user'), min: 80 }),
    date: autoColWidth(list.map(s => s.date), { header: t('usage.date'), min: 100 }),
    modelName: autoColWidth(list.map(s => s.modelName), { header: t('usage.model'), min: 100 }),
    count: autoColWidth(list.map(s => s.count), { header: t('usage.calls') }),
    prompt: autoColWidth(list.map(s => fmt(s.promptTokens)), { header: t('usage.promptTokens') }),
    completion: autoColWidth(list.map(s => fmt(s.completionTokens)), { header: t('usage.completionTokens') }),
    reasoning: autoColWidth(list.map(s => fmt(s.reasoningTokens)), { header: t('usage.reasoningTokens') }),
    cached: autoColWidth(list.map(s => fmt(s.cachedTokens)), { header: t('usage.cachedTokens') }),
    thinkingCount: autoColWidth(list.map(s => s.thinkingCount || 0), { header: t('usage.thinkingCount') })
  }
})

/* ========== 图表渲染 (纯SVG) ========== */
function metricValue(r, metric) {
  if (metric === 'totalTokens') return (r.promptTokens || 0) + (r.completionTokens || 0) + (r.reasoningTokens || 0)
  return r[metric] || 0
}
function aggregateBy(data, keyFn, metric) {
  const result = {}
  data.forEach(r => {
    const key = keyFn(r) || '-'
    result[key] = (result[key] || 0) + metricValue(r, metric)
  })
  return result
}

// 多用户对比调色板（系列按选择顺序取色，循环使用）
const CHART_PALETTE = ['#6366f1', '#10b981', '#f59e0b', '#ef4444', '#06b6d4', '#a855f7', '#ec4899', '#84cc16']

const lineChartSvg = computed(() => {
  if (!chartData.value.length) return `<div class="us-chart-empty">${esc(t('usage.chartEmpty'))}</div>`
  // 多用户对比模式：每个用户一条折线
  if (chartUserDim.value.length > 1) {
    const dates = [...new Set(chartData.value.map(r => r.date))].sort()
    const series = chartUserDim.value.map(u => {
      const byDate = aggregateBy(chartData.value.filter(r => r.username === u), r => r.date, lineMetric.value)
      return { name: u, values: dates.map(d => byDate[d] || 0) }
    })
    return buildMultiLineSvg(dates, series, lineMetric.value)
  }
  const byDate = aggregateBy(chartData.value, r => r.date, lineMetric.value)
  const dates = Object.keys(byDate).sort()
  const values = dates.map(d => byDate[d])
  return buildLineSvg(dates, values, lineMetric.value)
})

const barChartSvg = computed(() => {
  if (!chartData.value.length) return `<div class="us-chart-empty">${esc(t('usage.chartEmpty'))}</div>`
  // 多用户对比模式：按模型分组的并排柱
  if (chartUserDim.value.length > 1) {
    const byModelAll = aggregateBy(chartData.value, r => r.modelName, barMetric.value)
    const models = Object.keys(byModelAll).sort((a, b) => byModelAll[b] - byModelAll[a]).slice(0, 8)
    const series = chartUserDim.value.map(u => {
      const byModel = aggregateBy(chartData.value.filter(r => r.username === u), r => r.modelName, barMetric.value)
      return { name: u, values: models.map(m => byModel[m] || 0) }
    })
    return buildGroupedBarSvg(models, series, barMetric.value)
  }
  const byModel = aggregateBy(chartData.value, r => r.modelName, barMetric.value)
  const models = Object.keys(byModel).sort((a, b) => byModel[b] - byModel[a]).slice(0, 10)
  const values = models.map(m => byModel[m])
  return buildBarSvg(models, values, barMetric.value)
})

// 指标展示名随语言切换，改为函数式取字典
function metricName(metric) {
  const map = {
    count: t('usage.calls'),
    promptTokens: t('usage.promptTokens'),
    completionTokens: t('usage.completionTokens'),
    reasoningTokens: t('usage.reasoningTokens'),
    cachedTokens: t('usage.cachedTokens'),
    thinkingCount: t('usage.thinkingMode'),
    totalTokens: t('usage.totalTokens')
  }
  return map[metric] || metric
}

function buildLineSvg(labels, values, metric) {
  const w = 720, h = 320
  const padL = 60, padR = 30, padT = 30, padB = 60
  const chartW = w - padL - padR, chartH = h - padT - padB
  const maxV = Math.max(...values)
  const niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1
  const points = values.map((v, i) => ({
    x: padL + (labels.length === 1 ? chartW / 2 : (i / (labels.length - 1)) * chartW),
    y: padT + chartH - (v / niceMax) * chartH,
    v, label: labels[i]
  }))
  const valueName = metricName(metric)
  let svg = `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">`
  for (let i = 0; i <= 4; i++) {
    const yy = padT + chartH - (i / 4) * chartH
    svg += `<line class="us-chart-axis" x1="${padL}" y1="${yy}" x2="${w - padR}" y2="${yy}" stroke-opacity="0.15"/>`
    svg += `<text class="us-chart-label" x="${padL - 8}" y="${yy + 4}" text-anchor="end">${fmtShort(niceMax * i / 4)}</text>`
  }
  if (points.length > 1) {
    const path = 'M ' + points.map(p => `${p.x} ${p.y}`).join(' L ')
    svg += `<path d="${path}" fill="none" stroke="var(--primary, #6366f1)" stroke-width="2.5" stroke-linejoin="round" stroke-linecap="round"/>`
    svg += `<path d="${path} L ${points[points.length-1].x} ${padT+chartH} L ${points[0].x} ${padT+chartH} Z" fill="var(--primary, #6366f1)" fill-opacity="0.1" stroke="none"/>`
  }
  points.forEach(p => {
    svg += `<circle class="us-chart-point" cx="${p.x}" cy="${p.y}" r="5" fill="var(--primary, #6366f1)" stroke="var(--bg-2, #1e1e2e)" stroke-width="2"><title>${esc(p.label)}\n${valueName}：${fmt(p.v)}</title></circle>`
  })
  const skipStep = labels.length > 8 ? Math.ceil(labels.length / 8) : 1
  points.forEach((p, i) => {
    if (i % skipStep !== 0 && i !== points.length - 1) return
    const dl = (p.label && p.label.length >= 10) ? p.label.substring(5) : (p.label || '')
    svg += `<text class="us-chart-label" x="${p.x}" y="${padT + chartH + 18}" text-anchor="middle">${esc(dl)}</text>`
  })
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 8}">${esc(valueName + ' · ' + t('usage.byDateTrend'))}</text>`
  svg += '</svg>'
  return svg
}

function buildBarSvg(labels, values, metric) {
  const w = 720, h = 360
  const padL = 60, padR = 20, padT = 30, padB = 110
  const chartW = w - padL - padR, chartH = h - padT - padB
  const maxV = Math.max(...values)
  const niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1
  const step = chartW / Math.max(labels.length, 1)
  const barW = Math.min(40, step * 0.6)
  const valueName = metricName(metric)
  let svg = `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">`
  for (let i = 0; i <= 4; i++) {
    const yy = padT + chartH - (i / 4) * chartH
    svg += `<line class="us-chart-axis" x1="${padL}" y1="${yy}" x2="${w - padR}" y2="${yy}" stroke-opacity="0.15"/>`
    svg += `<text class="us-chart-label" x="${padL - 8}" y="${yy + 4}" text-anchor="end">${fmtShort(niceMax * i / 4)}</text>`
  }
  values.forEach((v, i) => {
    const x = padL + i * step + (step - barW) / 2
    const bh = (v / niceMax) * chartH
    const y = padT + chartH - bh
    svg += `<rect class="us-chart-bar" x="${x}" y="${y}" width="${barW}" height="${bh}" rx="3"><title>${esc(labels[i] || '')}\n${valueName}：${fmt(v)}</title></rect>`
    svg += `<text class="us-chart-value-label" x="${x + barW/2}" y="${y - 6}">${fmtShort(v)}</text>`
    let lbl = labels[i] || ''
    if (lbl.length > 14) lbl = lbl.substring(0, 13) + '…'
    svg += `<text class="us-chart-label" x="${x + barW/2}" y="${padT + chartH + 16}" text-anchor="end" transform="rotate(-30 ${x + barW/2} ${padT + chartH + 16})">${esc(lbl)}</text>`
  })
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 12}">${esc(valueName + ' · ' + t('usage.byModelTop10'))}</text>`
  svg += '</svg>'
  return svg
}

/**
 * 构建图例（色点 + 用户名），返回 svg 片段与占用高度。
 * 宽度按字符估算（CJK≈12px，ASCII≈7px @font-size 11），超出画布宽度自动换行。
 */
function buildLegend(series, padL, w) {
  const estW = name => [...String(name)].reduce((s, ch) => s + (ch.charCodeAt(0) > 255 ? 12 : 7), 0)
  let x = padL, y = 16, svg = ''
  series.forEach((s, i) => {
    const itemW = 14 + estW(s.name) + 16
    if (x + itemW > w - 10 && x > padL) { x = padL; y += 16 }
    const color = CHART_PALETTE[i % CHART_PALETTE.length]
    svg += `<rect x="${x}" y="${y - 8}" width="10" height="10" rx="2" fill="${color}"/>`
    svg += `<text class="us-chart-label" x="${x + 14}" y="${y + 1}">${esc(s.name)}</text>`
    x += itemW
  })
  return { svg, height: y + 8 }
}

/**
 * 多用户折线对比图：series = [{ name, values }]，values 与 labels 等长（缺失日期已补 0）
 */
function buildMultiLineSvg(labels, series, metric) {
  const w = 720, h = 340
  const padL = 60, padR = 30, padB = 60
  const legend = buildLegend(series, padL, w)
  const padT = legend.height + 12
  const chartW = w - padL - padR, chartH = h - padT - padB
  const maxV = Math.max(...series.flatMap(s => s.values))
  const niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1
  const valueName = metricName(metric)
  const xAt = i => padL + (labels.length === 1 ? chartW / 2 : (i / (labels.length - 1)) * chartW)
  let svg = `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">`
  svg += legend.svg
  for (let i = 0; i <= 4; i++) {
    const yy = padT + chartH - (i / 4) * chartH
    svg += `<line class="us-chart-axis" x1="${padL}" y1="${yy}" x2="${w - padR}" y2="${yy}" stroke-opacity="0.15"/>`
    svg += `<text class="us-chart-label" x="${padL - 8}" y="${yy + 4}" text-anchor="end">${fmtShort(niceMax * i / 4)}</text>`
  }
  series.forEach((s, si) => {
    const color = CHART_PALETTE[si % CHART_PALETTE.length]
    const points = s.values.map((v, i) => ({ x: xAt(i), y: padT + chartH - (v / niceMax) * chartH, v }))
    if (points.length > 1) {
      const path = 'M ' + points.map(p => `${p.x} ${p.y}`).join(' L ')
      svg += `<path d="${path}" fill="none" stroke="${color}" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>`
    }
    points.forEach((p, i) => {
      svg += `<circle class="us-chart-point" cx="${p.x}" cy="${p.y}" r="4" fill="${color}" stroke="var(--bg-2, #1e1e2e)" stroke-width="1.5"><title>${esc(s.name)} · ${esc(labels[i])}\n${valueName}：${fmt(p.v)}</title></circle>`
    })
  })
  const skipStep = labels.length > 8 ? Math.ceil(labels.length / 8) : 1
  labels.forEach((lb, i) => {
    if (i % skipStep !== 0 && i !== labels.length - 1) return
    const dl = (lb && lb.length >= 10) ? lb.substring(5) : (lb || '')
    svg += `<text class="us-chart-label" x="${xAt(i)}" y="${padT + chartH + 18}" text-anchor="middle">${esc(dl)}</text>`
  })
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 8}">${esc(valueName + ' · ' + t('usage.byDateTrendMulti'))}</text>`
  svg += '</svg>'
  return svg
}

/**
 * 多用户分组柱状对比图：每个模型一组，组内每用户一根柱
 */
function buildGroupedBarSvg(labels, series, metric) {
  const w = 720, h = 380
  const padL = 60, padR = 20, padB = 110
  const legend = buildLegend(series, padL, w)
  const padT = legend.height + 12
  const chartW = w - padL - padR, chartH = h - padT - padB
  const maxV = Math.max(...series.flatMap(s => s.values))
  const niceMax = maxV > 0 ? Math.ceil(maxV * 1.15) : 1
  const step = chartW / Math.max(labels.length, 1)
  const barW = Math.min(28, (step * 0.72) / Math.max(series.length, 1))
  const valueName = metricName(metric)
  let svg = `<svg viewBox="0 0 ${w} ${h}" xmlns="http://www.w3.org/2000/svg" preserveAspectRatio="xMidYMid meet" style="width:100%;height:100%;">`
  svg += legend.svg
  for (let i = 0; i <= 4; i++) {
    const yy = padT + chartH - (i / 4) * chartH
    svg += `<line class="us-chart-axis" x1="${padL}" y1="${yy}" x2="${w - padR}" y2="${yy}" stroke-opacity="0.15"/>`
    svg += `<text class="us-chart-label" x="${padL - 8}" y="${yy + 4}" text-anchor="end">${fmtShort(niceMax * i / 4)}</text>`
  }
  labels.forEach((m, i) => {
    const groupX = padL + i * step + (step - barW * series.length) / 2
    series.forEach((s, si) => {
      const v = s.values[i]
      const bh = (v / niceMax) * chartH
      const x = groupX + si * barW
      const y = padT + chartH - bh
      const color = CHART_PALETTE[si % CHART_PALETTE.length]
      svg += `<rect class="us-chart-bar-multi" x="${x}" y="${y}" width="${Math.max(barW - 2, 2)}" height="${bh}" rx="2" fill="${color}"><title>${esc(s.name)} · ${esc(m || '')}\n${valueName}：${fmt(v)}</title></rect>`
    })
    let lbl = m || ''
    if (lbl.length > 14) lbl = lbl.substring(0, 13) + '…'
    const cx = padL + i * step + step / 2
    svg += `<text class="us-chart-label" x="${cx}" y="${padT + chartH + 16}" text-anchor="end" transform="rotate(-30 ${cx} ${padT + chartH + 16})">${esc(lbl)}</text>`
  })
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 12}">${esc(valueName + ' · ' + t('usage.byModelTop8Multi'))}</text>`
  svg += '</svg>'
  return svg
}
</script>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.5);
  touch-action: none;
  overscroll-behavior: none;
}
.us-modal-container {
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 12px;
  width: 92%;
  max-width: 900px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--border, #333);
}
.us-header-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.us-eyebrow {
  font-size: 14px;
  color: var(--ink, #eee);
}
.us-scope-badge {
  font-size: 10px;
  padding: 3px 8px;
  border-radius: 4px;
}
.us-scope-admin {
  background: rgba(99,102,241,0.15);
  color: #818cf8;
}
.us-scope-self {
  background: rgba(34,197,94,0.15);
  color: #4ade80;
}
.modal-close {
  background: none;
  border: none;
  color: var(--ink-3, #999);
  cursor: pointer;
  font-size: 16px;
}
/* 汇总卡片（用户统计tab内） */
.us-stat-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  margin-bottom: 16px;
}
.us-stat-card {
  background: var(--paper-2, #2a2a3e);
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  padding: 12px 10px;
  text-align: center;
  transition: border-color 0.2s, transform 0.15s;
}
.us-stat-card:hover {
  border-color: var(--primary, #6366f1);
  transform: translateY(-1px);
}
.us-stat-card.tick {
  animation: us-tick-anim 0.5s ease;
}
@keyframes us-tick-anim {
  0% { transform: scale(1); }
  30% { transform: scale(1.08); }
  100% { transform: scale(1); }
}
.us-stat-num {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink, #eee);
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}
.us-stat-label {
  font-size: 10px;
  color: var(--ink-3, #999);
  margin-top: 4px;
  letter-spacing: 0.03em;
}
.us-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}
.us-subtab-nav {
  display: flex;
  gap: 4px;
  margin-bottom: 16px;
}
.us-subtab-item {
  padding: 6px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  color: var(--ink-2, #ccc);
}
.us-subtab-item.active {
  background: var(--primary, #6366f1);
  color: #fff;
}
.us-search-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: flex-end;
  margin-bottom: 14px;
}
.us-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.us-label {
  font-size: 11px;
  color: var(--ink-3, #999);
}
.us-input {
  padding: 6px 10px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: var(--paper, #252536);
  color: var(--ink, #eee);
  font-size: 12px;
  outline: none;
  width: 130px;
}
/* 查询条件下拉（Element Plus el-select）暗色适配，风格对齐后台管理 */
.us-el-select { width: 150px; }
.us-el-select-wide { width: 190px; }
.us-el-select-multi { width: 250px; }
.us-el-select :deep(.el-select__wrapper) {
  background: var(--paper, #252536);
  box-shadow: 0 0 0 1px var(--border, #333) inset;
  min-height: 30px;
  font-size: 12px;
}
.us-el-select :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--primary, #6366f1) inset;
}
.us-el-select :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary, #6366f1) inset;
}
.us-el-select :deep(.el-select__placeholder) {
  color: var(--ink, #eee);
  font-size: 12px;
}
.us-el-select :deep(.el-select__placeholder.is-transparent) {
  color: var(--ink-3, #999);
}
.us-el-select :deep(.el-select__selected-item) {
  color: var(--ink, #eee);
}
.us-el-select :deep(.el-select__input) {
  color: var(--ink, #eee);
  font-size: 12px;
}
.us-el-select :deep(.el-select__caret),
.us-el-select :deep(.el-select__clear) {
  color: var(--ink-3, #999);
}
.us-el-select :deep(.el-tag) {
  background: rgba(99,102,241,0.15);
  color: #818cf8;
  border-color: transparent;
}
.us-el-select :deep(.el-tag .el-tag__close) {
  color: #818cf8;
}
.us-actions {
  display: flex;
  gap: 6px;
}
.us-btn {
  padding: 6px 14px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: transparent;
  color: var(--ink-2, #ccc);
  cursor: pointer;
  font-size: 12px;
}
.us-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.us-btn-primary {
  background: var(--primary, #6366f1);
  border-color: var(--primary, #6366f1);
  color: #fff;
}
/* el-table 弹窗主题适配：跟随弹窗 CSS 变量，暗色/亮色自动切换 */
.us-el-table {
  --el-table-border-color: var(--border, #333);
  --el-table-header-bg-color: var(--paper, #252536);
  --el-table-header-text-color: var(--ink-3, #999);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--paper-2, #2a2a3e);
  --el-table-text-color: var(--ink-2, #ccc);
  --el-fill-color-lighter: var(--paper, #252536);
  --el-text-color-secondary: var(--ink-4, #666);
  font-size: 12px;
  border-radius: 8px;
  overflow: hidden;
}
/* 单元格强制单行展示，超出列宽省略（与后台管理一致） */
.us-el-table :deep(.cell) {
  white-space: nowrap;
  text-overflow: ellipsis;
}
.us-el-table :deep(.el-table__cell) {
  padding: 6px 0;
}
.us-thinking-badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--paper-2, #2a2a3e);
  color: var(--ink-3, #999);
}
.us-thinking-badge.on {
  background: rgba(99,102,241,0.15);
  color: #818cf8;
}
.us-pagination {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 12px;
}
.us-page-info {
  font-size: 12px;
  color: var(--ink-2, #ccc);
  font-variant-numeric: tabular-nums;
}
.us-page-size {
  padding: 5px 28px 5px 8px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background-color: var(--paper, #252536);
  /* 与 Element Plus 下拉一致的单个 chevron 箭头 */
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%237f8d9f' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");
  background-position: calc(100% - 8px) center;
  background-size: 14px 14px;
  background-repeat: no-repeat;
  color: var(--ink-2, #ccc);
  font-size: 12px;
  outline: none;
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
}
.us-page-size:hover { border-color: var(--primary, #6366f1); }
/* Mini tabs */
.us-mini-tab-nav {
  display: flex;
  gap: 4px;
  margin-bottom: 14px;
}
.us-mini-tab-item {
  padding: 5px 14px;
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  color: var(--ink-3, #999);
  transition: all 0.2s;
}
.us-mini-tab-item:hover { color: var(--ink-2, #ccc); background: var(--paper-2, #2a2a3e); }
.us-mini-tab-item.active { color: var(--primary, #6366f1); background: rgba(99,102,241,0.1); }
/* Chart toolbar */
.us-chart-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.us-metric-label {
  font-size: 11px;
  color: var(--ink-3, #999);
  flex-shrink: 0;
}
.us-metric-selector {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
}
.us-metric-btn {
  padding: 4px 10px;
  border: 1px solid var(--border, #333);
  border-radius: 5px;
  background: transparent;
  color: var(--ink-3, #999);
  cursor: pointer;
  font-size: 11px;
  transition: all 0.2s;
}
.us-metric-btn:hover { color: var(--primary, #6366f1); border-color: var(--primary, #6366f1); }
.us-metric-btn.active { color: var(--primary, #6366f1); background: rgba(99,102,241,0.1); border-color: var(--primary, #6366f1); font-weight: 500; }
/* Chart canvas */
.us-chart-canvas {
  min-height: 260px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--paper, #252536);
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  padding: 16px;
  overflow: hidden;
}
.us-chart-canvas :deep(svg) { max-width: 100%; height: auto; display: block; }
.us-chart-empty {
  color: var(--ink-4, #666);
  font-size: 13px;
  text-align: center;
  padding: 40px 0;
}
.us-chart-canvas :deep(.us-chart-bar) { fill: var(--primary, #6366f1); fill-opacity: 0.85; transition: fill-opacity 0.15s; cursor: pointer; }
.us-chart-canvas :deep(.us-chart-bar:hover) { fill-opacity: 1; }
.us-chart-canvas :deep(.us-chart-bar-multi) { fill-opacity: 0.85; transition: fill-opacity 0.15s; cursor: pointer; }
.us-chart-canvas :deep(.us-chart-bar-multi:hover) { fill-opacity: 1; }
.us-chart-canvas :deep(.us-chart-axis) { stroke: var(--ink-3, #999); stroke-width: 1; }
.us-chart-canvas :deep(.us-chart-label) { fill: var(--ink-3, #999); font-size: 11px; }
.us-chart-canvas :deep(.us-chart-value-label) { fill: var(--ink-2, #ccc); font-size: 11px; text-anchor: middle; }
.us-chart-canvas :deep(.us-chart-axis-label) { fill: var(--ink-3, #999); font-size: 11px; }
.us-chart-canvas :deep(.us-chart-point) { transition: transform 0.15s; cursor: pointer; transform-box: fill-box; transform-origin: center; }
.us-chart-canvas :deep(.us-chart-point:hover) { transform: scale(1.5); }
/* 日期范围选择器暗色适配 */
.us-date-range {
  --el-date-editor-width: 240px;
}
.us-date-range :deep(.el-input__wrapper) {
  background: var(--paper, #252536);
  border-color: var(--border, #333);
  box-shadow: none;
}
.us-date-range :deep(.el-input__inner) {
  color: var(--ink, #eee);
  font-size: 12px;
}
.us-date-range :deep(.el-range-separator) {
  color: var(--ink-3, #999);
  font-size: 12px;
}
.us-date-range :deep(.el-range-input) {
  color: var(--ink, #eee);
  font-size: 12px;
  background: transparent;
}
.us-date-range :deep(.el-range__icon),
.us-date-range :deep(.el-range__close-icon) {
  color: var(--ink-3, #999);
}
</style>

<!-- 全局样式：约束 el-date-picker 弹出面板在移动端不引起背景滚动 -->
<style>
.el-picker-panel,
.el-date-range-picker {
  overscroll-behavior: none;
  touch-action: manipulation;
}
.el-picker-panel * {
  overscroll-behavior: contain;
}

/* 移动端日期范围选择器适配：默认双列日历(table-cell 并排)+左侧快捷栏(absolute width:110px)
   面板宽 646px，窄屏严重溢出；改为单列日历上下堆叠 + 顶部水平滚动快捷栏，
   面板宽度 min(360px, 视口-16px) 适配窄屏 */
@media (max-width: 768px) {
  .el-date-range-picker {
    width: min(360px, calc(100vw - 16px)) !important;
  }
  .el-date-range-picker .el-picker-panel__body {
    min-width: 0 !important;
  }
  /* 两个日历从 table-cell 并排改为 block 上下堆叠 */
  .el-date-range-picker__content {
    display: block !important;
    width: 100% !important;
  }
  .el-date-range-picker__content.is-left {
    border-right: none !important;
    border-bottom: 1px solid var(--el-datepicker-inner-border-color, var(--el-border-color-light)) !important;
  }
  /* 月份标题左右内边距由 50px 收窄为 20px，防止窄屏标题文字溢出 */
  .el-date-range-picker__content .el-date-range-picker__header div {
    margin-left: 20px !important;
    margin-right: 20px !important;
  }
  /* 快捷栏从左侧绝对定位(width:110px) 改为顶部水平滚动条 */
  .el-picker-panel__sidebar {
    position: static !important;
    width: 100% !important;
    height: auto !important;
    padding: 8px 10px !important;
    display: flex !important;
    gap: 6px !important;
    overflow-x: auto !important;
    border-right: none !important;
    border-bottom: 1px solid var(--el-datepicker-inner-border-color, var(--el-border-color-light)) !important;
  }
  /* 快捷项默认是 width:100% 的块级文字行，横排后会每个撑满一行宽；
     重置为自适应宽度的胶囊按钮，带边框/背景以体现可点击 */
  .el-picker-panel__shortcut {
    width: auto !important;
    line-height: 1 !important;
    padding: 6px 12px !important;
    font-size: 12px !important;
    white-space: nowrap !important;
    flex-shrink: 0 !important;
    border: 1px solid var(--el-border-color) !important;
    border-radius: 999px !important;
    background: var(--el-fill-color-light) !important;
    color: var(--el-text-color-regular) !important;
    cursor: pointer !important;
  }
  .el-picker-panel__shortcut:active,
  .el-picker-panel__shortcut:hover {
    color: var(--el-color-primary) !important;
    border-color: var(--el-color-primary) !important;
    background: var(--el-color-primary-light-9, rgba(99,102,241,0.1)) !important;
  }
  /* body 不再为左侧快捷栏让出 110px 左边距 */
  .el-picker-panel__sidebar + .el-picker-panel__body,
  .el-picker-panel [slot=sidebar] + .el-picker-panel__body {
    margin-left: 0 !important;
  }

  /* 弹层改为固定居中：堆叠后面板高度超出视口时，popper 贴输入框定位会把下半月历
     挤出屏幕外（横向贴边同理被遮挡）；!important 覆盖 popper 的内联定位样式 */
  .us-date-popper.el-popper {
    position: fixed !important;
    top: 50% !important;
    left: 50% !important;
    transform: translate(-50%, -50%) !important;
    margin: 0 !important;
  }
  .us-date-popper .el-popper__arrow {
    display: none !important;
  }
  /* 面板限高 + 内部纵向滚动，兜底极矮视口（如横屏手机）下仍可完整操作 */
  .us-date-popper .el-date-range-picker {
    max-height: calc(100dvh - 24px);
    overflow-y: auto;
    overscroll-behavior: contain;
    -webkit-overflow-scrolling: touch;
  }
}
</style>
