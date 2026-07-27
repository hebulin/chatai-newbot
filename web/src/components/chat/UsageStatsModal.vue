<template>
  <Teleport to="body">
    <div class="modal-overlay" @click="$emit('close')">
      <div class="us-modal-container" @click.stop>
        <div class="modal-header">
          <div class="us-header-top">
            <span class="us-eyebrow"><b>ANALYTICS</b> · 数据统计</span>
            <span class="us-scope-badge" :class="isAdmin ? 'us-scope-admin' : 'us-scope-self'">
              {{ isAdmin ? '全站视图 · ALL USERS' : '个人视图 · ONLY ME' }}
            </span>
          </div>
          <button class="modal-close" @click="$emit('close')">✕</button>
        </div>

        <!-- 子Tab -->
        <div class="us-body">
          <div class="us-subtab-nav">
            <div class="us-subtab-item" :class="{ active: subTab === 'usage' }" @click="subTab = 'usage'">使用记录</div>
            <div class="us-subtab-item" :class="{ active: subTab === 'stats' }" @click="switchToStats">用户统计</div>
          </div>

          <!-- 使用记录 -->
          <div v-show="subTab === 'usage'" class="us-sub-content">
            <div class="us-search-bar">
              <div v-if="isAdmin" class="us-field">
                <label class="us-label">用户</label>
                <select v-model="usageFilter.username" class="us-input us-select">
                  <option value="">全部用户</option>
                  <option v-for="u in usernames" :key="u" :value="u">{{ u }}</option>
                </select>
              </div>
              <div class="us-field">
                <label class="us-label">模型</label>
                <select v-model="usageFilter.modelName" class="us-input us-select">
                  <option value="">全部模型</option>
                  <option v-for="m in modelNames" :key="m" :value="m">{{ m }}</option>
                </select>
              </div>
              <div class="us-field">
                <label class="us-label">日期范围</label>
                <el-date-picker
                  v-model="usageDateRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  value-format="YYYY-MM-DD"
                  :shortcuts="dateShortcuts"
                  class="us-date-range"
                  size="small"
                />
              </div>
              <div class="us-actions">
                <button class="us-btn us-btn-primary" @click="searchUsage">搜索</button>
                <button class="us-btn" @click="resetUsageFilter">重置</button>
              </div>
            </div>
            <div class="us-table-wrap">
              <table class="us-table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th v-if="isAdmin">用户</th>
                    <th>模型</th>
                    <th>输入Token</th>
                    <th>输出Token</th>
                    <th>思考Token</th>
                    <th>缓存Token</th>
                    <th>思考模式</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-if="usageLogs.length === 0"><td :colspan="isAdmin ? 8 : 7" class="us-empty">暂无数据</td></tr>
                  <tr v-for="(log, i) in usageLogs" :key="i">
                    <td>{{ log.time }}</td>
                    <td v-if="isAdmin">{{ log.username }}</td>
                    <td>{{ log.modelName }}</td>
                    <td>{{ fmt(log.promptTokens) }}</td>
                    <td>{{ fmt(log.completionTokens) }}</td>
                    <td>{{ fmt(log.reasoningTokens) }}</td>
                    <td>{{ fmt(log.cachedTokens) }}</td>
                    <td><span class="us-thinking-badge" :class="{ on: log.deepThinking }">{{ log.deepThinking ? '深度' : '标准' }}</span></td>
                  </tr>
                </tbody>
              </table>
            </div>
            <div class="us-pagination">
              <select v-model.number="usageSize" class="us-page-size" @change="usagePage = 1; loadUsageLogs()">
                <option :value="10">10条/页</option>
                <option :value="20">20条/页</option>
                <option :value="50">50条/页</option>
              </select>
              <button class="us-btn" :disabled="usagePage <= 1" @click="usagePage--; loadUsageLogs()">上一页</button>
              <span class="us-page-info">第 {{ usagePage }} / {{ usageTotalPages }} 页 · 共 {{ usageTotal }} 条</span>
              <button class="us-btn" :disabled="usagePage >= usageTotalPages" @click="usagePage++; loadUsageLogs()">下一页</button>
            </div>
          </div>

          <!-- 用户统计 -->
          <div v-show="subTab === 'stats'" class="us-sub-content">
            <div class="us-search-bar">
              <div v-if="isAdmin" class="us-field">
                <label class="us-label">用户</label>
                <select v-model="statsFilter.username" class="us-input us-select">
                  <option value="">全部用户</option>
                  <option v-for="u in usernames" :key="u" :value="u">{{ u }}</option>
                </select>
              </div>
              <div class="us-field">
                <label class="us-label">日期范围</label>
                <el-date-picker
                  v-model="statsDateRange"
                  type="daterange"
                  range-separator="至"
                  start-placeholder="开始日期"
                  end-placeholder="结束日期"
                  value-format="YYYY-MM-DD"
                  :shortcuts="dateShortcuts"
                  class="us-date-range"
                  size="small"
                />
              </div>
              <div class="us-actions">
                <button class="us-btn us-btn-primary" @click="searchStats">搜索</button>
                <button class="us-btn" @click="resetStatsFilter">重置</button>
              </div>
            </div>

            <!-- 四维汇总卡片 -->
            <div class="us-stat-cards">
              <div class="us-stat-card" :class="{ tick: tickFlags.count }">
                <div class="us-stat-num">{{ statsSummary.count }}</div>
                <div class="us-stat-label">调用次数 · CALLS</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.promptTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.promptTokens) }}</div>
                <div class="us-stat-label">输入Token · INPUT</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.completionTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.completionTokens) }}</div>
                <div class="us-stat-label">输出Token · OUTPUT</div>
              </div>
              <div class="us-stat-card" :class="{ tick: tickFlags.reasoningTokens }">
                <div class="us-stat-num">{{ fmtShort(statsSummary.reasoningTokens) }}</div>
                <div class="us-stat-label">思考Token · REASONING</div>
              </div>
            </div>

            <!-- 迷你子Tab：列表 / 曲线图 / 柱状图 -->
            <div class="us-mini-tab-nav">
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'list' }" @click="miniTab = 'list'">结果列表</div>
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'line' }" @click="miniTab = 'line'">曲线图</div>
              <div class="us-mini-tab-item" :class="{ active: miniTab === 'bar' }" @click="miniTab = 'bar'">柱状图</div>
            </div>

            <!-- 列表 -->
            <div v-show="miniTab === 'list'">
              <div class="us-table-wrap">
                <table class="us-table">
                  <thead>
                    <tr>
                      <th v-if="isAdmin">用户</th>
                      <th>日期</th>
                      <th>模型</th>
                      <th>调用次数</th>
                      <th>输入Token</th>
                      <th>输出Token</th>
                      <th>思考Token</th>
                      <th>缓存Token</th>
                      <th>思考模式次数</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-if="userStats.length === 0"><td :colspan="isAdmin ? 9 : 8" class="us-empty">点击「搜索」查看统计结果</td></tr>
                    <tr v-for="(s, i) in userStats" :key="i">
                      <td v-if="isAdmin">{{ s.username }}</td>
                      <td>{{ s.date }}</td>
                      <td>{{ s.modelName }}</td>
                      <td>{{ s.count }}</td>
                      <td>{{ fmt(s.promptTokens) }}</td>
                      <td>{{ fmt(s.completionTokens) }}</td>
                      <td>{{ fmt(s.reasoningTokens) }}</td>
                      <td>{{ fmt(s.cachedTokens) }}</td>
                      <td>{{ s.thinkingCount || 0 }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <div class="us-pagination">
                <select v-model.number="statsPageSize" class="us-page-size" @change="statsPage = 1; loadUserStats()">
                  <option :value="10">10条/页</option>
                  <option :value="20">20条/页</option>
                  <option :value="50">50条/页</option>
                </select>
                <button class="us-btn" :disabled="statsPage <= 1" @click="statsPage--; loadUserStats()">上一页</button>
                <span class="us-page-info">第 {{ statsPage }} / {{ statsTotalPages }} 页 · 共 {{ statsTotal }} 条</span>
                <button class="us-btn" :disabled="statsPage >= statsTotalPages" @click="statsPage++; loadUserStats()">下一页</button>
              </div>
            </div>

            <!-- 曲线图 -->
            <div v-show="miniTab === 'line'">
              <div class="us-chart-toolbar">
                <span class="us-metric-label">指标</span>
                <div class="us-metric-selector">
                  <button v-for="m in lineMetrics" :key="m.key" class="us-metric-btn" :class="{ active: lineMetric === m.key }" @click="lineMetric = m.key">{{ m.label }}</button>
                </div>
              </div>
              <div class="us-chart-canvas" v-html="lineChartSvg"></div>
            </div>

            <!-- 柱状图 -->
            <div v-show="miniTab === 'bar'">
              <div class="us-chart-toolbar">
                <span class="us-metric-label">指标</span>
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
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { getUsageLogs, getUserStats, getUsernames } from '@/api/usage'

defineEmits(['close'])

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
const statsFilter = ref({ username: '' })

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

// 日期快捷选项
const dateShortcuts = [
  { text: '今天', value: () => { const d = new Date(); return [d, d] } },
  { text: '本周', value: () => { const end = new Date(); const start = new Date(); const day = start.getDay() || 7; start.setDate(start.getDate() - day + 1); return [start, end] } },
  { text: '本月', value: () => { const end = new Date(); const start = new Date(end.getFullYear(), end.getMonth(), 1); return [start, end] } },
  { text: '最近30天', value: () => { const end = new Date(); const start = new Date(); start.setDate(start.getDate() - 29); return [start, end] } }
]

// 图表指标（与旧版对齐：曲线 6 指标、柱状 6 指标，含缓存Token/思考模式）
const lineMetric = ref('count')
const barMetric = ref('totalTokens')
const lineMetrics = [
  { key: 'count', label: '调用次数' },
  { key: 'promptTokens', label: '输入Token' },
  { key: 'completionTokens', label: '输出Token' },
  { key: 'reasoningTokens', label: '思考Token' },
  { key: 'cachedTokens', label: '缓存Token' },
  { key: 'thinkingCount', label: '思考模式' }
]
const barMetrics = [
  { key: 'totalTokens', label: 'Token总量' },
  { key: 'count', label: '调用次数' },
  { key: 'promptTokens', label: '输入Token' },
  { key: 'completionTokens', label: '输出Token' },
  { key: 'reasoningTokens', label: '思考Token' },
  { key: 'cachedTokens', label: '缓存Token' }
]

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
  nextTick(setupScrollShadow)
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
    if (isNaN(s.getTime()) || isNaN(e.getTime())) return '日期格式不合法'
    if (s > e) return '结束日期不能早于开始日期'
    const diffDays = (e - s) / 86400000
    if (diffDays > 31) return '日期范围不能超过 31 天'
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
  } catch (e) { ElMessage.error('加载使用记录失败') }
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
    if (statsFilter.value.username) params.username = statsFilter.value.username
    if (statsDateRange.value && statsDateRange.value[0]) params.startDate = statsDateRange.value[0]
    if (statsDateRange.value && statsDateRange.value[1]) params.endDate = statsDateRange.value[1]
    const data = await getUserStats(params)
    if (data && data.success) {
      userStats.value = data.data || []
      statsTotal.value = data.total || 0
      statsTotalPages.value = data.totalPages || 1
    }
  } catch (e) { ElMessage.error('加载用户统计失败') }
}

async function loadCharts() {
  try {
    const params = { getAll: true, size: 10000 }
    if (statsFilter.value.username) params.username = statsFilter.value.username
    if (statsDateRange.value && statsDateRange.value[0]) params.startDate = statsDateRange.value[0]
    if (statsDateRange.value && statsDateRange.value[1]) params.endDate = statsDateRange.value[1]
    const data = await getUserStats(params)
    if (data && data.success) {
      chartData.value = data.data || []
    }
  } catch (e) { ElMessage.error('加载统计数据失败') }
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
  statsFilter.value = { username: '' }
  statsDateRange.value = getLast30Days()
  searchStats()
}

// 表格横向滚动阴影：滚动到非右边缘时显示右侧阴影提示
function setupScrollShadow() {
  document.querySelectorAll('.us-modal-container .us-table-wrap').forEach(wrap => {
    if (wrap.__shadowBound) return
    wrap.__shadowBound = true
    const update = () => {
      const atEnd = wrap.scrollLeft + wrap.clientWidth >= wrap.scrollWidth - 2
      wrap.classList.toggle('is-scrolled', !atEnd && wrap.scrollWidth > wrap.clientWidth)
    }
    wrap.addEventListener('scroll', update)
    update()
  })
}

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

const lineChartSvg = computed(() => {
  if (!chartData.value.length) return '<div class="us-chart-empty">暂无统计数据，请先搜索</div>'
  const byDate = aggregateBy(chartData.value, r => r.date, lineMetric.value)
  const dates = Object.keys(byDate).sort()
  const values = dates.map(d => byDate[d])
  return buildLineSvg(dates, values, lineMetric.value)
})

const barChartSvg = computed(() => {
  if (!chartData.value.length) return '<div class="us-chart-empty">暂无统计数据，请先搜索</div>'
  const byModel = aggregateBy(chartData.value, r => r.modelName, barMetric.value)
  const models = Object.keys(byModel).sort((a, b) => byModel[b] - byModel[a]).slice(0, 10)
  const values = models.map(m => byModel[m])
  return buildBarSvg(models, values, barMetric.value)
})

const METRIC_NAMES = { count: '调用次数', promptTokens: '输入Token', completionTokens: '输出Token', reasoningTokens: '思考Token', cachedTokens: '缓存Token', thinkingCount: '思考模式', totalTokens: 'Token总量' }

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
  const valueName = METRIC_NAMES[metric] || metric
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
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 8}">${esc(valueName + ' · 按日期趋势')}</text>`
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
  const valueName = METRIC_NAMES[metric] || metric
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
  svg += `<text class="us-chart-axis-label" x="${padL}" y="${h - 12}">${esc(valueName + ' · 按模型 Top 10')}</text>`
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
.us-select {
  cursor: pointer;
  appearance: none;
  -webkit-appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--ink-3, #999) 50%), linear-gradient(135deg, var(--ink-3, #999) 50%, transparent 50%);
  background-position: calc(100% - 14px) center, calc(100% - 9px) center;
  background-size: 5px 5px, 5px 5px;
  background-repeat: no-repeat;
  padding-right: 26px;
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
.us-table-wrap {
  overflow-x: auto;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  position: relative;
  transition: box-shadow 0.15s;
}
/* 横向滚动阴影：未滚动到右边缘时显示右侧阴影提示 */
.us-table-wrap.is-scrolled {
  box-shadow: inset -10px 0 8px -8px rgba(0,0,0,0.25);
}
.us-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.us-table th {
  padding: 10px 12px;
  text-align: left;
  background: var(--paper, #252536);
  color: var(--ink-3, #999);
  font-weight: 500;
  white-space: nowrap;
}
.us-table td {
  padding: 9px 12px;
  border-top: 1px solid var(--border, #333);
  color: var(--ink-2, #ccc);
  white-space: nowrap;
}
.us-empty {
  text-align: center;
  color: var(--ink-4, #666);
  padding: 24px !important;
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
  padding: 5px 8px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: var(--paper, #252536);
  color: var(--ink-2, #ccc);
  font-size: 12px;
  outline: none;
  cursor: pointer;
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
    padding: 6px 0 !important;
    display: flex !important;
    overflow-x: auto !important;
    border-right: none !important;
    border-bottom: 1px solid var(--el-datepicker-inner-border-color, var(--el-border-color-light)) !important;
  }
  .el-picker-panel__shortcut {
    white-space: nowrap !important;
    flex-shrink: 0 !important;
  }
  /* body 不再为左侧快捷栏让出 110px 左边距 */
  .el-picker-panel__sidebar + .el-picker-panel__body,
  .el-picker-panel [slot=sidebar] + .el-picker-panel__body {
    margin-left: 0 !important;
  }
}
</style>
