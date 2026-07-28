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
          <div class="settings-sidebar">
            <div class="settings-menu-item" :class="{ active: tab === 'changePassword' }" @click="tab = 'changePassword'">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              <span>修改密码</span>
            </div>
            <div class="settings-menu-item" :class="{ active: tab === 'systemPrompt' }" @click="tab = 'systemPrompt'; loadPresets()">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/></svg>
              <span>提示词</span>
            </div>
            <div class="settings-menu-item" :class="{ active: tab === 'dataManagement' }" @click="tab = 'dataManagement'">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
              <span>数据管理</span>
            </div>
          </div>
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

            <!-- 数据管理 -->
            <div v-if="tab === 'dataManagement'" class="settings-panel">
              <h3 class="settings-panel-title">数据管理</h3>
              <div class="data-mgmt-rows">
                <div class="data-mgmt-row">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">导出全部历史对话</div>
                    <div class="data-mgmt-desc">将当前账号下的全部会话记录（{{ chatStore.countValidChats() }} 条）导出为 TXT 文本文件</div>
                  </div>
                  <button class="settings-btn settings-btn-primary" @click="confirmExport">导出</button>
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
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { changePassword } from '@/api/auth'
import { getPromptPresets, savePromptPresets } from '@/api/user'
import { useChatStore } from '@/stores/chat'
import { useAuthStore } from '@/stores/auth'

const emit = defineEmits(['close', 'logout'])
const chatStore = useChatStore()
const authStore = useAuthStore()

const tab = ref('changePassword')

// 修改密码
const pwdForm = ref({ oldPwd: '', newPwd: '', confirmPwd: '' })
const pwdTip = ref('')
const pwdTipError = ref(false)

// 提示词预设
const presets = ref([])
const promptTip = ref('')
const promptTipError = ref(false)

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

function confirmExport() {
  const count = chatStore.countValidChats()
  if (count === 0) {
    ElMessage.info('当前没有可导出的会话')
    return
  }
  ElMessageBox.confirm(`确定导出全部 ${count} 条历史对话吗？`, '导出确认', {
    confirmButtonText: '导出',
    cancelButtonText: '取消'
  }).then(() => {
    chatStore.exportChats()
    ElMessage.success('已导出')
  }).catch(() => {})
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
.settings-sidebar {
  width: 180px;
  border-right: 1px solid var(--border, #333);
  padding: 12px;
  flex-shrink: 0;
}
.settings-menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--ink-2, #ccc);
  margin-bottom: 4px;
}
.settings-menu-item:hover {
  background: var(--paper-2, #2a2a3e);
}
.settings-menu-item.active {
  background: var(--paper-2, #2a2a3e);
  color: var(--primary, #6366f1);
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

/* === 移动端：侧栏改为顶部水平标签栏 === */
@media (max-width: 480px) {
  .modal-container {
    width: 96%;
    max-height: 90vh;
  }
  .settings-modal {
    flex-direction: column;
  }
  .settings-sidebar {
    width: 100%;
    display: flex;
    flex-direction: row;
    flex-wrap: nowrap;
    border-right: none;
    border-bottom: 1px solid var(--border, #333);
    padding: 8px 12px;
    gap: 6px;
    overflow-x: auto;
    flex-shrink: 0;
  }
  .settings-menu-item {
    white-space: nowrap;
    flex-shrink: 0;
    padding: 8px 12px;
    font-size: 12px;
    margin-bottom: 0;
  }
  .settings-content {
    padding: 16px 14px;
  }
  .data-mgmt-row {
    flex-wrap: wrap;
  }
}
</style>
