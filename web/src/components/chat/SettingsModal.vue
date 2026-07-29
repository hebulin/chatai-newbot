<template>
  <Teleport to="body">
    <div class="modal-overlay" @click="$emit('close')">
      <div class="modal-container settings-modal-container" @click.stop>
        <!-- Autofill trap: prevent browser from filling username into sidebar search -->
        <div style="display:none" aria-hidden="true">
          <input type="text" autocomplete="username" tabindex="-1" />
          <input type="password" autocomplete="current-password" tabindex="-1" />
        </div>
        <div class="modal-header">
          <span class="modal-title">设置</span>
          <button class="modal-close" @click="$emit('close')">✕</button>
        </div>
        <div class="settings-modal">
          <!-- 导航：el-tabs 仅作菜单栏（内容区隐藏），桌面端左侧竖排、窄屏顶部横排可滑动 -->
          <el-tabs v-model="tab" :tab-position="isMobile ? 'top' : 'left'" class="settings-tabs-nav" @tab-change="onTabChange">
            <el-tab-pane name="changePassword">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                  <span>修改密码</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="systemPrompt">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/></svg>
                  <span>提示词</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="loginDevices">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                  <span>登录管理</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="shareManage">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
                  <span>分享管理</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="dataManagement">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
                  <span>数据管理</span>
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>
          <div class="settings-content">
            <!-- 修改密码 -->
            <div v-if="tab === 'changePassword'" class="settings-panel">
              <h3 class="settings-panel-title">修改密码</h3>
              <div class="settings-form-group">
                <label>当前密码</label>
                <input type="password" v-model="pwdForm.oldPwd" class="settings-input" placeholder="请输入当前密码" autocomplete="new-password" />
              </div>
              <div class="settings-form-group">
                <label>新密码</label>
                <input type="password" v-model="pwdForm.newPwd" class="settings-input" placeholder="请输入新密码（至少4位）" autocomplete="new-password" />
              </div>
              <div class="settings-form-group">
                <label>确认新密码</label>
                <input type="password" v-model="pwdForm.confirmPwd" class="settings-input" placeholder="请再次输入新密码" autocomplete="new-password" />
                <div v-if="pwdTip" class="settings-form-tip" :class="{ error: pwdTipError }">{{ pwdTip }}</div>
              </div>
              <div class="settings-form-actions">
                <button class="settings-btn settings-btn-primary" @click="submitChangePassword">提交</button>
              </div>
              <div class="settings-form-note">提交成功后将自动退出登录，请使用新密码重新登录。</div>
            </div>

            <!-- 提示词 -->
            <div v-if="tab === 'systemPrompt'" class="settings-panel">
              <h3 class="settings-panel-title">提示词</h3>
              <div class="preset-list">
                <div v-for="(p, idx) in presets" :key="p.id || idx" class="preset-item" :class="{ enabled: p.enabled }">
                  <div class="preset-item-head">
                    <input v-model="p.title" class="settings-input preset-title-input" maxlength="50" placeholder="提示词名称（如：翻译、技术顾问）" />
                    <span class="preset-enable" :class="{ active: p.enabled }" @click="toggleEnable(idx)" :title="p.enabled ? '点击取消启用' : '点击启用（最多启用 1 条）'">
                      <span class="preset-enable-dot"></span>
                      {{ p.enabled ? '已启用' : '启用' }}
                    </span>
                    <button class="preset-del-btn" @click="removePreset(idx)" title="删除">✕</button>
                  </div>
                  <textarea v-model="p.content" class="settings-input settings-textarea preset-content-input" rows="4" placeholder="提示词内容（启用后作为全局 System Prompt 注入）"></textarea>
                </div>
                <div v-if="presets.length === 0" class="preset-empty">暂无提示词，点击下方“添加提示词”按钮新建</div>
              </div>
              <div v-if="promptTip" class="settings-form-tip" :class="{ error: promptTipError }">{{ promptTip }}</div>
              <div class="settings-form-actions preset-actions">
                <button class="settings-btn settings-btn-ghost" @click="addPreset">+ 添加提示词</button>
                <button class="settings-btn settings-btn-primary" @click="savePresets">保存</button>
              </div>
              <div class="settings-form-note">可保存多条提示词，但最多只能启用其中 1 条；启用的提示词会在每次对话时作为全局提示词生效。都不启用则使用系统默认提示词。</div>
            </div>

            <!-- 登录管理 -->
            <div v-if="tab === 'loginDevices'" class="settings-panel">
              <h3 class="settings-panel-title">登录管理</h3>
              <div v-if="sessionsLoading" class="session-empty">加载中...</div>
              <div v-else class="session-list">
                <div v-for="s in sessions" :key="s.sessionId" class="session-item" :class="{ current: s.current }">
                  <div class="session-icon">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                  </div>
                  <div class="session-info">
                    <div class="session-title">
                      {{ s.browser || '未知浏览器' }}
                      <span v-if="s.current" class="session-current-badge">当前设备</span>
                    </div>
                    <div class="session-meta">
                      <span>IP：{{ s.ip || '未知' }}</span>
                      <span>登录时间：{{ s.createdAt || '未知' }}</span>
                    </div>
                  </div>
                  <button v-if="!s.current" class="settings-btn settings-btn-danger session-kick-btn" @click="confirmKick(s)">踢下线</button>
                </div>
                <div v-if="sessions.length === 0" class="session-empty">暂无登录设备记录</div>
              </div>
              <div class="settings-form-note">展示当前账号在各设备终端的登录会话；踢下线后对应设备需重新登录。同一浏览器重复登录会产生多条会话记录。</div>
            </div>

            <!-- 分享管理 -->
            <div v-if="tab === 'shareManage'" class="settings-panel">
              <h3 class="settings-panel-title">分享管理</h3>
              <div class="share-toolbar">
                <select v-model="shareFilterStatus" class="settings-select">
                  <option value="">全部状态</option>
                  <option value="valid">有效</option>
                  <option value="expired">已过期</option>
                  <option value="orphaned">会话已删</option>
                </select>
                <button class="settings-btn settings-btn-ghost" :disabled="invalidShares.length === 0" @click="handleClearInvalidShares">清除失效（{{ invalidShares.length }}）</button>
                <button class="settings-btn settings-btn-danger" :disabled="selectedShareIds.length === 0" @click="handleDeleteSelectedShares">删除选中（{{ selectedShareIds.length }}）</button>
              </div>
              <div v-if="sharesLoading" class="session-empty">加载中...</div>
              <div v-else class="share-list">
                <div v-for="s in filteredShares" :key="s.id" class="share-item" :class="{ invalid: s.status !== 'valid' }">
                  <input type="checkbox" class="share-check" :value="s.id" v-model="selectedShareIds" />
                  <div class="share-info">
                    <div class="share-title-line">
                      <span class="share-title">{{ s.title }}</span>
                      <span class="share-status" :class="s.status === 'valid' ? 'ok' : 'bad'">{{ shareStatusText(s.status) }}</span>
                    </div>
                    <div class="share-meta">
                      <span>创建：{{ s.createdAt || '未知' }}</span>
                      <span>有效期至：{{ s.expiresAt || '永久' }}</span>
                    </div>
                    <div class="share-link">{{ shareUrl(s) }}</div>
                  </div>
                  <div class="share-actions">
                    <button class="share-action-btn" title="复制链接" @click="copyShareLink(s)">复制</button>
                    <button class="share-action-btn" title="新窗口打开" @click="openShareLink(s)">打开</button>
                    <button class="share-action-btn danger" title="删除分享" @click="handleDeleteShare(s)">删除</button>
                  </div>
                </div>
                <div v-if="filteredShares.length === 0" class="session-empty">暂无分享记录</div>
              </div>
              <div class="settings-form-note">仅展示当前账号创建的分享链接；删除后对应链接立即失效。失效分享 = 已过期或源会话已被删除。</div>
            </div>

            <!-- 数据管理 -->
            <div v-if="tab === 'dataManagement'" class="settings-panel">
              <h3 class="settings-panel-title">数据管理</h3>
              <div class="data-mgmt-rows">
                <div class="data-mgmt-row">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">导出全部历史对话</div>
                    <div class="data-mgmt-desc">将当前账号下的全部会话记录（{{ chatStore.countValidChats() }} 条）按所选格式导出；JSON 为含元信息的完整备份，可用于下方导入恢复</div>
                  </div>
                  <select v-model="exportFormat" class="settings-select">
                    <option value="txt">TXT 文本</option>
                    <option value="md">Markdown</option>
                    <option value="json">JSON 备份</option>
                  </select>
                  <button class="settings-btn settings-btn-primary" @click="confirmExport">导出</button>
                </div>
                <div class="data-mgmt-row">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">导入 JSON 备份</div>
                    <div class="data-mgmt-desc">从备份文件恢复会话；按会话合并，已存在的会话不会被覆盖</div>
                  </div>
                  <button class="settings-btn settings-btn-primary" @click="importFileRef && importFileRef.click()">选择文件</button>
                  <input ref="importFileRef" type="file" accept=".json,application/json" style="display:none" @change="handleImportJson" />
                </div>
                <div class="data-mgmt-row data-mgmt-row-danger">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">删除全部对话</div>
                    <div class="data-mgmt-desc">清空当前账号下的全部聊天记录，删除后无法恢复</div>
                  </div>
                  <button class="settings-btn settings-btn-danger" @click="confirmDeleteAll">删除</button>
                </div>
              </div>
              <div class="settings-form-note">导出文件由浏览器保存到本地；删除操作会同步清除服务端数据。</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import { changePassword, getSessions, kickSession } from '@/api/auth'
import { getPromptPresets, savePromptPresets } from '@/api/user'
import { getMyShares, deleteShare, batchDeleteMyShares } from '@/api/share'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'

const emit = defineEmits(['close', 'logout'])
const chatStore = useChatStore()
const authStore = useAuthStore()

const tab = ref('changePassword')

// 窄屏检测：≤480px 时导航改为顶部横排 Tab（与样式媒体查询断点一致）
const mobileQuery = window.matchMedia('(max-width: 480px)')
const isMobile = ref(mobileQuery.matches)
const onMobileChange = e => { isMobile.value = e.matches }
onMounted(() => mobileQuery.addEventListener('change', onMobileChange))
onBeforeUnmount(() => mobileQuery.removeEventListener('change', onMobileChange))

// 切换 Tab 时按需加载对应数据
function onTabChange(name) {
  if (name === 'systemPrompt') loadPresets()
  else if (name === 'loginDevices') loadSessions()
  else if (name === 'shareManage') loadShares()
}

// 修改密码
const pwdForm = ref({ oldPwd: '', newPwd: '', confirmPwd: '' })
const pwdTip = ref('')
const pwdTipError = ref(false)

// 提示词预设
const presets = ref([])
const promptTip = ref('')
const promptTipError = ref(false)

// 登录设备管理
const sessions = ref([])
const sessionsLoading = ref(false)

// 分享管理
const shares = ref([])
const sharesLoading = ref(false)
const shareFilterStatus = ref('')
const selectedShareIds = ref([])

// 导出格式（txt/md/json）
const exportFormat = ref('txt')

async function submitChangePassword() {
  pwdTip.value = ''
  pwdTipError.value = false
  const { oldPwd, newPwd, confirmPwd } = pwdForm.value
  if (!oldPwd || !newPwd || !confirmPwd) {
    pwdTip.value = '请完整填写所有密码字段'
    pwdTipError.value = true
    return
  }
  if (newPwd.length < 4) {
    pwdTip.value = '新密码长度不能少于4位'
    pwdTipError.value = true
    return
  }
  if (newPwd !== confirmPwd) {
    pwdTip.value = '两次输入的新密码不一致'
    pwdTipError.value = true
    return
  }
  if (oldPwd === newPwd) {
    pwdTip.value = '新密码不能与当前密码相同'
    pwdTipError.value = true
    return
  }
  try {
    const data = await changePassword({ oldPassword: oldPwd, newPassword: newPwd, confirmPassword: confirmPwd })
    if (data.success) {
      ElMessage.success('密码修改成功，即将退出登录')
      setTimeout(() => {
        emit('close')
        emit('logout')
      }, 1200)
    } else {
      pwdTip.value = data.message || '修改失败'
      pwdTipError.value = true
    }
  } catch (e) {
    pwdTip.value = '请求失败'
    pwdTipError.value = true
  }
}

async function loadPresets() {
  try {
    const data = await getPromptPresets()
    if (data && data.success) {
      presets.value = (data.presets || []).map(p => ({
        id: p.id || '',
        title: p.title || '',
        content: p.content || '',
        enabled: !!p.enabled
      }))
    }
  } catch (e) { /* ignore */ }
}

function addPreset() {
  presets.value.push({ id: '', title: '', content: '', enabled: false })
}

function removePreset(idx) {
  presets.value.splice(idx, 1)
}

// 启用互斥：点击某条则仅其启用、其余取消；再次点击已启用条则取消启用
function toggleEnable(idx) {
  const next = !presets.value[idx].enabled
  presets.value.forEach((p, i) => { p.enabled = next && i === idx })
}

async function savePresets() {
  promptTip.value = ''
  promptTipError.value = false
  if (presets.value.length > 20) {
    promptTip.value = '提示词数量过多（最多 20 条）'
    promptTipError.value = true
    return
  }
  if (presets.value.some(p => (p.content || '').length > 20000)) {
    promptTip.value = '单条提示词内容过长（最多 20000 字符）'
    promptTipError.value = true
    return
  }
  try {
    const data = await savePromptPresets(presets.value)
    if (data.success) {
      presets.value = (data.presets || []).map(p => ({
        id: p.id || '',
        title: p.title || '',
        content: p.content || '',
        enabled: !!p.enabled
      }))
      promptTip.value = '已保存，立即对所有新对话生效。'
      promptTipError.value = false
      ElMessage.success('提示词已保存')
    } else {
      promptTip.value = data.message || '保存失败'
      promptTipError.value = true
    }
  } catch (e) {
    promptTip.value = '保存失败'
    promptTipError.value = true
  }
}

const EXPORT_FORMAT_TEXT = { txt: 'TXT 文本', md: 'Markdown', json: 'JSON 备份' }

function confirmExport() {
  const count = chatStore.countValidChats()
  if (count === 0) {
    ElMessage.info('当前没有可导出的会话')
    return
  }
  const fmt = EXPORT_FORMAT_TEXT[exportFormat.value] || 'TXT 文本'
  ElMessageBox.confirm(`确定将全部 ${count} 条历史对话导出为 ${fmt} 吗？`, '导出确认', {
    confirmButtonText: '导出',
    cancelButtonText: '取消'
  }).then(async () => {
    // 懒加载模式下导出前需先补齐未加载的会话正文，会话多时耗时较长，全屏 loading 提示进度
    const loading = ElLoading.service({
      lock: true,
      text: `正在导出 ${count} 条会话，请稍候…`,
      background: 'rgba(0, 0, 0, 0.5)'
    })
    try {
      await chatStore.exportChats(exportFormat.value)
      ElMessage.success('已导出')
    } catch (e) {
      ElMessage.error('导出失败：拉取全量会话内容失败，请重试')
    } finally {
      loading.close()
    }
  }).catch(() => {})
}

// 导入 JSON 备份：解析文件后按会话合并，导入成功后自动同步到服务端
const importFileRef = ref(null)
async function handleImportJson(e) {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  try {
    const text = await file.text()
    const data = JSON.parse(text)
    const { imported, skipped } = chatStore.importChatsJson(data)
    if (imported > 0) {
      ElMessage.success(`导入完成：新增 ${imported} 条会话` + (skipped > 0 ? `，跳过 ${skipped} 条已存在/无效会话` : ''))
    } else {
      ElMessage.info(skipped > 0 ? `未导入新会话（${skipped} 条已存在或无效）` : '备份文件中没有会话')
    }
  } catch (err) {
    ElMessage.error('导入失败：' + (err.message || '文件不是有效的备份 JSON'))
  }
}

function confirmDeleteAll() {
  const count = chatStore.countValidChats()
  if (count === 0) {
    ElMessage.info('当前没有可删除的会话')
    return
  }
  ElMessageBox.confirm(`此操作将删除全部 ${count} 条会话及其聊天记录，删除后无法恢复。确定继续？`, '删除全部对话', {
    confirmButtonText: '删除',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    chatStore.deleteAllChats()
    ElMessage.success('已删除全部对话')
  }).catch(() => {})
}

async function loadSessions() {
  sessionsLoading.value = true
  try {
    const data = await getSessions()
    if (data && data.success) {
      // 当前设备置顶，其余按登录时间倒序（接口已排序）
      sessions.value = (data.sessions || []).sort((a, b) => (b.current ? 1 : 0) - (a.current ? 1 : 0))
    }
  } catch (e) { /* ignore */ } finally {
    sessionsLoading.value = false
  }
}

function confirmKick(s) {
  ElMessageBox.confirm(`确定踢掉该设备（${s.browser || '未知浏览器'} / ${s.ip || '未知IP'}）吗？踢下线后该设备需重新登录。`, '踢下线确认', {
    confirmButtonText: '踢下线',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    try {
      const data = await kickSession(s.sessionId)
      if (data.success) {
        ElMessage.success('已踢下线')
      } else {
        ElMessage.error(data.message || '操作失败')
      }
    } catch (e) { /* 拦截器已提示 */ }
    loadSessions()
  }).catch(() => {})
}

// ===== 分享管理（仅管理当前账号自己的分享，功能口径与后台分享管理一致） =====

const SHARE_STATUS_TEXT = { valid: '有效', expired: '已过期', orphaned: '会话已删' }
function shareStatusText(s) {
  return SHARE_STATUS_TEXT[s] || s
}

// 失效分享 = 已过期 + 源会话已删
const invalidShares = computed(() => shares.value.filter(s => s.status !== 'valid'))

const filteredShares = computed(() =>
  shareFilterStatus.value ? shares.value.filter(s => s.status === shareFilterStatus.value) : shares.value
)

function shareUrl(s) {
  return location.origin + '/share/' + s.id
}

async function loadShares() {
  sharesLoading.value = true
  selectedShareIds.value = []
  try {
    const res = await getMyShares()
    if (res?.success) shares.value = res.data || []
  } catch (e) { /* ignore */ } finally {
    sharesLoading.value = false
  }
}

async function copyShareLink(s) {
  try {
    await navigator.clipboard.writeText(shareUrl(s))
    ElMessage.success('链接已复制')
  } catch (e) {
    // 非 https 环境剪贴板可能不可用，降级弹窗展示
    ElMessageBox.alert(shareUrl(s), '分享链接（请手动复制）', { confirmButtonText: '知道了' })
  }
}

function openShareLink(s) {
  window.open(shareUrl(s), '_blank')
}

// 单条删除（撤销分享）
async function handleDeleteShare(s) {
  try {
    await ElMessageBox.confirm(`确定删除分享 "${s.title}" 吗？删除后该链接立即失效。`, '确认删除', { type: 'warning' })
    const res = await deleteShare(s.id)
    if (res?.success) {
      ElMessage.success('已删除')
      await loadShares()
    } else {
      ElMessage.error(res?.message || '删除失败')
    }
  } catch { /* cancelled */ }
}

// 批量删除选中
async function handleDeleteSelectedShares() {
  const ids = selectedShareIds.value
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(`确定删除选中的 ${ids.length} 条分享吗？删除后链接立即失效。`, '确认批量删除', { type: 'warning' })
    await doBatchDeleteShares(ids)
  } catch { /* cancelled */ }
}

// 一键清除失效（已过期 + 源会话已删）
async function handleClearInvalidShares() {
  const rows = invalidShares.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(
      `共 ${rows.length} 条失效分享（已过期或源会话已删除），确定全部清除吗？`,
      '确认清除失效', { type: 'warning' }
    )
    await doBatchDeleteShares(rows.map(r => r.id))
  } catch { /* cancelled */ }
}

async function doBatchDeleteShares(ids) {
  const res = await batchDeleteMyShares(ids)
  if (res?.success) {
    ElMessage.success(`已删除 ${res.deleted ?? ids.length} 条分享`)
    await loadShares()
  } else {
    ElMessage.error(res?.message || '删除失败')
  }
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
}
.modal-container {
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 12px;
  width: 90%;
  max-width: 720px;
  max-height: 80vh;
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
.modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink, #eee);
}
.modal-close {
  background: none;
  border: none;
  color: var(--ink-3, #999);
  cursor: pointer;
  font-size: 16px;
}
.settings-modal {
  display: flex;
  flex: 1;
  overflow: hidden;
}
/* === 导航 Tab（el-tabs 仅作菜单栏，内容区由 .settings-content 承担） === */
.settings-tabs-nav {
  flex-shrink: 0;
}
.settings-tabs-nav :deep(.el-tabs__content) {
  display: none;
}
.settings-tabs-nav :deep(.el-tabs__header.is-left) {
  width: 160px;
  margin-right: 0;
  padding: 12px 0;
}
.settings-tabs-nav :deep(.el-tabs__item) {
  color: var(--ink-2, #ccc);
  font-size: 13px;
}
.settings-tabs-nav :deep(.el-tabs__item.is-left) {
  justify-content: flex-start;
  text-align: left;
  padding: 0 16px;
  height: 38px;
}
.settings-tabs-nav :deep(.el-tabs__item:hover),
.settings-tabs-nav :deep(.el-tabs__item.is-active) {
  color: var(--primary, #6366f1);
}
.settings-tabs-nav :deep(.el-tabs__active-bar) {
  background-color: var(--primary, #6366f1);
}
.settings-tabs-nav :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--border, #333);
}
.settings-tab-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.settings-tab-label svg {
  flex-shrink: 0;
}
.settings-content {
  flex: 1;
  padding: 20px;
  overflow-y: auto;
}
.settings-panel-title {
  font-size: 15px;
  font-weight: 600;
  margin-bottom: 16px;
  color: var(--ink, #eee);
}
.settings-form-group {
  margin-bottom: 14px;
}
.settings-form-group label {
  display: block;
  font-size: 12px;
  color: var(--ink-3, #999);
  margin-bottom: 6px;
}
.settings-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: var(--paper, #252536);
  color: var(--ink, #eee);
  font-size: 13px;
  outline: none;
}
.settings-input:focus {
  border-color: var(--primary, #6366f1);
}
.settings-textarea {
  resize: vertical;
  min-height: 120px;
}
.settings-form-tip {
  font-size: 12px;
  margin-top: 4px;
  color: var(--ink-3, #999);
}
.settings-form-tip.error {
  color: #ef4444;
}
.settings-form-actions {
  margin-top: 16px;
}
.settings-btn {
  padding: 8px 20px;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
}
.settings-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.settings-select {
  padding: 8px 10px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: var(--paper, #252536);
  color: var(--ink, #eee);
  font-size: 13px;
  outline: none;
  cursor: pointer;
  flex-shrink: 0;
}
.settings-select:focus {
  border-color: var(--primary, #6366f1);
}
.settings-btn-primary {
  background: var(--primary, #6366f1);
  color: #fff;
}
.settings-btn-danger {
  background: #ef4444;
  color: #fff;
}
.settings-form-note {
  margin-top: 16px;
  font-size: 11px;
  color: var(--ink-4, #666);
}
.data-mgmt-rows {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.data-mgmt-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
}
.data-mgmt-info {
  flex: 1;
}
.data-mgmt-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink, #eee);
}
.data-mgmt-desc {
  font-size: 11px;
  color: var(--ink-3, #999);
  margin-top: 4px;
}

/* === 提示词预设管理 === */
.preset-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.preset-item {
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  padding: 12px;
}
.preset-item.enabled {
  border-color: var(--primary, #6366f1);
}
.preset-item-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.preset-title-input {
  flex: 1;
}
.preset-enable {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  font-size: 12px;
  color: var(--ink-3, #999);
  cursor: pointer;
  white-space: nowrap;
  flex-shrink: 0;
}
.preset-enable.active {
  border-color: var(--primary, #6366f1);
  color: var(--primary, #6366f1);
}
.preset-enable-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1px solid currentColor;
}
.preset-enable.active .preset-enable-dot {
  background: var(--primary, #6366f1);
}
.preset-del-btn {
  background: none;
  border: none;
  color: var(--ink-3, #999);
  cursor: pointer;
  font-size: 14px;
  padding: 4px 6px;
  flex-shrink: 0;
}
.preset-del-btn:hover {
  color: #ef4444;
}
.preset-content-input {
  min-height: 80px;
}
.preset-empty {
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3, #999);
  border: 1px dashed var(--border, #333);
  border-radius: 8px;
}
.preset-actions {
  display: flex;
  gap: 10px;
}
.settings-btn-ghost {
  background: transparent;
  border: 1px solid var(--border, #333);
  color: var(--ink-2, #ccc);
}
.settings-btn-ghost:hover {
  border-color: var(--primary, #6366f1);
  color: var(--primary, #6366f1);
}

/* === 登录设备管理 === */
.session-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
}
.session-item.current {
  border-color: var(--primary, #6366f1);
}
.session-icon {
  color: var(--ink-3, #999);
  flex-shrink: 0;
  display: flex;
  align-items: center;
}
.session-item.current .session-icon {
  color: var(--primary, #6366f1);
}
.session-info {
  flex: 1;
  min-width: 0;
}
.session-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink, #eee);
}
.session-current-badge {
  font-size: 10px;
  font-weight: 400;
  color: var(--primary, #6366f1);
  border: 1px solid var(--primary, #6366f1);
  border-radius: 4px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.session-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 11px;
  color: var(--ink-3, #999);
  margin-top: 4px;
}
.session-kick-btn {
  flex-shrink: 0;
  padding: 6px 14px;
}
.session-empty {
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-3, #999);
  border: 1px dashed var(--border, #333);
  border-radius: 8px;
}

/* === 分享管理 === */
.share-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.share-toolbar .settings-btn {
  padding: 8px 12px;
}
.share-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.share-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
}
.share-item.invalid {
  opacity: 0.75;
}
.share-check {
  flex-shrink: 0;
  width: 15px;
  height: 15px;
  accent-color: var(--primary, #6366f1);
  cursor: pointer;
}
.share-info {
  flex: 1;
  min-width: 0;
}
.share-title-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.share-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--ink, #eee);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.share-status {
  font-size: 10px;
  border-radius: 4px;
  padding: 1px 6px;
  flex-shrink: 0;
}
.share-status.ok {
  color: #22c55e;
  border: 1px solid #22c55e;
}
.share-status.bad {
  color: #ef4444;
  border: 1px solid #ef4444;
}
.share-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 14px;
  font-size: 11px;
  color: var(--ink-3, #999);
  margin-top: 4px;
}
.share-link {
  font-size: 11px;
  font-family: var(--mono, monospace);
  color: var(--ink-3, #999);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.share-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.share-action-btn {
  background: transparent;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  color: var(--ink-2, #ccc);
  cursor: pointer;
  font-size: 12px;
  padding: 5px 10px;
}
.share-action-btn:hover {
  border-color: var(--primary, #6366f1);
  color: var(--primary, #6366f1);
}
.share-action-btn.danger:hover {
  border-color: #ef4444;
  color: #ef4444;
}

/* === 移动端：侧栏改为顶部水平标签栏 === */
@media (max-width: 480px) {
  .modal-container {
    width: 96%;
    max-height: 90vh;
  }
  .settings-modal {
    flex-direction: column;
  }
  /* 顶部横排 Tab：紧凑尺寸，隐藏图标以尽量一屏放下；放不下时由 el-tabs 自带导航滚动 */
  .settings-tabs-nav :deep(.el-tabs__header.is-top) {
    margin: 0;
    padding: 0 8px;
  }
  .settings-tabs-nav :deep(.el-tabs__item.is-top) {
    padding: 0 10px;
    font-size: 12px;
    height: 40px;
  }
  .settings-tab-label {
    gap: 0;
  }
  .settings-tab-label svg {
    display: none;
  }
  .settings-content {
    padding: 16px 14px;
  }
  .data-mgmt-row {
    flex-wrap: wrap;
  }
  .session-item {
    flex-wrap: wrap;
  }
  .share-item {
    flex-wrap: wrap;
  }
  .share-actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
