<template>
  <Teleport to="body">
    <div class="modal-overlay" @click="requestClose">
      <div ref="modalContainer" class="modal-container settings-modal-container" role="dialog" aria-modal="true" :aria-label="t('settings.title')" tabindex="-1" @click.stop @keydown.esc="requestClose" @keydown.tab="trapModalFocus">
        <!-- Autofill trap: prevent browser from filling username into sidebar search -->
        <div style="display:none" aria-hidden="true">
          <input type="text" autocomplete="username" tabindex="-1" />
          <input type="password" autocomplete="current-password" tabindex="-1" />
        </div>
        <div class="modal-header">
          <span class="modal-title">{{ t('settings.title') }}</span>
          <button class="modal-close" type="button" :aria-label="t('common.close')" @click="requestClose">✕</button>
        </div>
        <div class="settings-modal">
          <!-- 导航：el-tabs 仅作菜单栏（内容区隐藏），桌面端左侧竖排、窄屏顶部横排可滑动 -->
          <el-tabs v-model="tab" :tab-position="isMobile ? 'top' : 'left'" class="settings-tabs-nav" @tab-change="onTabChange">
            <el-tab-pane name="general">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="4" y1="21" x2="4" y2="14"/><line x1="4" y1="10" x2="4" y2="3"/><line x1="12" y1="21" x2="12" y2="12"/><line x1="12" y1="8" x2="12" y2="3"/><line x1="20" y1="21" x2="20" y2="16"/><line x1="20" y1="12" x2="20" y2="3"/><line x1="1" y1="14" x2="7" y2="14"/><line x1="9" y1="8" x2="15" y2="8"/><line x1="17" y1="16" x2="23" y2="16"/></svg>
                  <span>{{ t('settings.tabGeneral') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="changePassword">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
                  <span>{{ t('settings.tabPassword') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="twoFactor">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/><path d="M9 12l2 2 4-4"/></svg>
                  <span>{{ t('settings.tabTwoFactor') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="systemPrompt">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 7V4h16v3"/><path d="M9 20h6"/><path d="M12 4v16"/></svg>
                  <span>{{ t('settings.tabPrompt') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="loginDevices">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                  <span>{{ t('settings.tabDevices') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="shareManage">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
                  <span>{{ t('settings.tabShares') }}</span>
                </span>
              </template>
            </el-tab-pane>
            <el-tab-pane name="dataManagement">
              <template #label>
                <span class="settings-tab-label">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><ellipse cx="12" cy="5" rx="9" ry="3"/><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"/><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"/></svg>
                  <span>{{ t('settings.tabData') }}</span>
                </span>
              </template>
            </el-tab-pane>
          </el-tabs>
          <div ref="settingsContent" class="settings-content">
            <!-- 通用：界面语言切换（用户端中英双语，管理后台保持中文） -->
            <div v-if="tab === 'general'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabGeneral') }}</h3>
              <div class="settings-form-group">
                <label>{{ t('settings.language') }}</label>
                <el-select v-model="uiLocale" class="settings-el-select" popper-class="settings-select-popper" @change="setLocale">
                  <el-option value="zh" label="中文" />
                  <el-option value="en" label="English" />
                </el-select>
              </div>
              <div class="settings-form-note">{{ t('settings.languageNote') }}</div>
            </div>

            <!-- 修改密码 -->
            <div v-if="tab === 'changePassword'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabPassword') }}</h3>
              <div class="settings-form-group">
                <label>{{ t('settings.oldPwd') }}</label>
                <input type="password" v-model="pwdForm.oldPwd" class="settings-input" :placeholder="t('settings.oldPwdPlaceholder')" autocomplete="new-password" />
              </div>
              <div class="settings-form-group">
                <label>{{ t('settings.newPwd') }}</label>
                <input type="password" v-model="pwdForm.newPwd" class="settings-input" :placeholder="t('settings.newPwdPlaceholder')" autocomplete="new-password" />
              </div>
              <div class="settings-form-group">
                <label>{{ t('settings.confirmPwd') }}</label>
                <input type="password" v-model="pwdForm.confirmPwd" class="settings-input" :placeholder="t('settings.confirmPwdPlaceholder')" autocomplete="new-password" />
                <div v-if="pwdTip" class="settings-form-tip" :class="{ error: pwdTipError }">{{ pwdTip }}</div>
              </div>
              <div class="settings-form-actions">
                <button class="settings-btn settings-btn-primary" @click="submitChangePassword">{{ t('settings.submit') }}</button>
              </div>
              <div class="settings-form-note">{{ t('settings.pwdNote') }}</div>
            </div>

            <!-- 双重验证：密码复核、扫码绑定、一次性恢复码与关闭流程 -->
            <div v-if="tab === 'twoFactor'" class="settings-panel" aria-labelledby="two-factor-settings-title">
              <h3 id="two-factor-settings-title" class="settings-panel-title">{{ t('settings.tabTwoFactor') }}</h3>
              <div v-if="twoFactorLoading" class="session-empty" aria-live="polite">{{ t('common.loading') }}</div>
              <template v-else-if="recoveryCodes.length">
                <div class="security-status enabled">
                  <span class="security-status-dot"></span>
                  <div>
                    <strong>{{ t('settings.twoFactorEnabled') }}</strong>
                    <p>{{ t('settings.recoveryCodesOneTime') }}</p>
                  </div>
                </div>
                <div class="recovery-code-grid" aria-label="20 recovery codes">
                  <code v-for="code in recoveryCodes" :key="code">{{ code }}</code>
                </div>
                <div class="settings-form-actions recovery-actions">
                  <button type="button" class="settings-btn settings-btn-ghost" @click="copyRecoveryCodes">{{ t('settings.copyRecoveryCodes') }}</button>
                  <button type="button" class="settings-btn settings-btn-ghost" @click="downloadRecoveryCodes">{{ t('settings.downloadRecoveryCodes') }}</button>
                </div>
                <label class="recovery-confirm">
                  <input v-model="recoveryCodesSaved" type="checkbox" />
                  <span>{{ t('settings.recoveryCodesSavedConfirm') }}</span>
                </label>
                <button type="button" class="settings-btn settings-btn-primary" :disabled="!recoveryCodesSaved" @click="finishRecoveryCodes">{{ t('settings.finishTwoFactorSetup') }}</button>
              </template>
              <template v-else-if="twoFactorSetup.setupToken">
                <p class="settings-form-note two-factor-intro">{{ t('settings.scanQrHelp') }}</p>
                <div class="qr-setup-layout">
                  <div class="qr-frame">
                    <img v-if="twoFactorQr" :src="twoFactorQr" :alt="t('settings.qrAlt')" />
                  </div>
                  <div class="manual-secret">
                    <span>{{ t('settings.manualSecret') }}</span>
                    <code>{{ twoFactorSetup.secret }}</code>
                  </div>
                </div>
                <div class="settings-form-group">
                  <label for="two-factor-setup-code">{{ t('settings.authenticatorCode') }}</label>
                  <input id="two-factor-setup-code" ref="setupCodeInput" v-model="twoFactorSetupCode" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" class="settings-input otp-input" placeholder="000000" :aria-invalid="!!twoFactorTip" aria-describedby="two-factor-setup-tip" @input="normalizeSetupCode" />
                  <div id="two-factor-setup-tip" class="settings-form-tip" :class="{ error: twoFactorTipError }" aria-live="polite">{{ twoFactorTip }}</div>
                </div>
                <div class="settings-form-actions inline-actions">
                  <button type="button" class="settings-btn settings-btn-primary" :disabled="twoFactorActionLoading" @click="confirmEnableTwoFactor">{{ t('settings.enableTwoFactor') }}</button>
                  <button type="button" class="settings-btn settings-btn-ghost" @click="cancelTwoFactorSetup">{{ t('common.cancel') }}</button>
                </div>
              </template>
              <template v-else-if="twoFactorStatus.enabled">
                <div class="security-status enabled">
                  <span class="security-status-dot"></span>
                  <div>
                    <strong>{{ t('settings.twoFactorEnabled') }}</strong>
                    <p>{{ t('settings.recoveryCodesRemaining', { n: twoFactorStatus.recoveryCodesRemaining }) }}</p>
                  </div>
                </div>
                <div class="security-section">
                  <h4>{{ t('settings.regenerateRecoveryCodes') }}</h4>
                  <p>{{ t('settings.regenerateHelp') }}</p>
                  <div class="settings-form-group">
                    <label for="two-factor-regenerate-password">{{ t('settings.oldPwd') }}</label>
                    <input id="two-factor-regenerate-password" v-model="regenerateForm.password" type="password" autocomplete="current-password" class="settings-input" />
                  </div>
                  <div class="settings-form-group">
                    <label for="two-factor-regenerate-code">{{ t('settings.authenticatorCode') }}</label>
                    <input id="two-factor-regenerate-code" v-model="regenerateForm.code" type="text" inputmode="numeric" autocomplete="one-time-code" maxlength="6" class="settings-input otp-input" placeholder="000000" @input="regenerateForm.code = regenerateForm.code.replace(/\D/g, '').slice(0, 6)" />
                  </div>
                  <button type="button" class="settings-btn settings-btn-ghost" :disabled="twoFactorActionLoading" @click="handleRegenerateRecoveryCodes">{{ t('settings.regenerate') }}</button>
                </div>
                <div class="security-section danger-zone">
                  <h4>{{ t('settings.disableTwoFactor') }}</h4>
                  <p>{{ t('settings.disableTwoFactorHelp') }}</p>
                  <div class="settings-form-group">
                    <label for="two-factor-disable-password">{{ t('settings.oldPwd') }}</label>
                    <input id="two-factor-disable-password" v-model="disableTwoFactorPassword" type="password" autocomplete="current-password" class="settings-input" />
                  </div>
                  <button type="button" class="settings-btn settings-btn-danger" :disabled="twoFactorActionLoading" @click="handleDisableTwoFactor">{{ t('settings.disableTwoFactor') }}</button>
                </div>
                <div class="settings-form-tip" :class="{ error: twoFactorTipError }" aria-live="polite">{{ twoFactorTip }}</div>
              </template>
              <template v-else>
                <div class="security-status">
                  <span class="security-status-dot"></span>
                  <div>
                    <strong>{{ t('settings.twoFactorDisabled') }}</strong>
                    <p>{{ t('settings.twoFactorIntro') }}</p>
                  </div>
                </div>
                <div class="settings-form-group">
                  <label for="two-factor-current-password">{{ t('settings.oldPwd') }}</label>
                  <input id="two-factor-current-password" v-model="setupPassword" type="password" autocomplete="current-password" class="settings-input" :aria-invalid="!!twoFactorTip" aria-describedby="two-factor-status-tip" />
                  <div id="two-factor-status-tip" class="settings-form-tip" :class="{ error: twoFactorTipError }" aria-live="polite">{{ twoFactorTip }}</div>
                </div>
                <button type="button" class="settings-btn settings-btn-primary" :disabled="twoFactorActionLoading" @click="startTwoFactorSetup">{{ t('settings.startTwoFactorSetup') }}</button>
              </template>
            </div>

            <!-- 提示词 -->
            <div v-if="tab === 'systemPrompt'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabPrompt') }}</h3>
              <div class="preset-list">
                <div v-for="(p, idx) in presets" :key="p.id || idx" class="preset-item" :class="{ enabled: p.enabled }">
                  <div class="preset-item-head">
                    <input v-model="p.title" class="settings-input preset-title-input" maxlength="50" :placeholder="t('settings.presetTitlePlaceholder')" />
                    <span class="preset-enable" :class="{ active: p.enabled }" @click="toggleEnable(idx)" :title="p.enabled ? t('settings.presetDisableTip') : t('settings.presetEnableTip')">
                      <span class="preset-enable-dot"></span>
                      {{ p.enabled ? t('settings.presetEnabled') : t('settings.presetEnable') }}
                    </span>
                    <button class="preset-del-btn" @click="removePreset(idx)" :title="t('common.delete')">✕</button>
                  </div>
                  <textarea v-model="p.content" class="settings-input settings-textarea preset-content-input" rows="4" :placeholder="t('settings.presetContentPlaceholder')"></textarea>
                </div>
                <div v-if="presets.length === 0" class="preset-empty">{{ t('settings.presetEmpty') }}</div>
              </div>
              <div v-if="promptTip" class="settings-form-tip" :class="{ error: promptTipError }">{{ promptTip }}</div>
              <div class="settings-form-actions preset-actions">
                <button class="settings-btn settings-btn-ghost" @click="addPreset">{{ t('settings.addPreset') }}</button>
                <button class="settings-btn settings-btn-primary" @click="savePresets">{{ t('common.save') }}</button>
              </div>
              <div class="settings-form-note">{{ t('settings.presetNote') }}</div>
            </div>

            <!-- 登录管理 -->
            <div v-if="tab === 'loginDevices'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabDevices') }}</h3>
              <div v-if="sessionsLoading" class="session-empty">{{ t('common.loading') }}</div>
              <div v-else class="session-list">
                <div v-for="s in sessions" :key="s.sessionId" class="session-item" :class="{ current: s.current }">
                  <div class="session-icon">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="3" width="20" height="14" rx="2" ry="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/></svg>
                  </div>
                  <div class="session-info">
                    <div class="session-title">
                      {{ s.browser || t('settings.unknownBrowser') }}
                      <span v-if="s.current" class="session-current-badge">{{ t('settings.currentDevice') }}</span>
                    </div>
                    <div class="session-meta">
                      <span>{{ t('settings.ip', { ip: s.ip || t('common.unknown') }) }}</span>
                      <span>{{ t('settings.loginTime', { time: s.createdAt || t('common.unknown') }) }}</span>
                    </div>
                  </div>
                  <button v-if="!s.current" class="settings-btn settings-btn-danger session-kick-btn" @click="confirmKick(s)">{{ t('settings.kick') }}</button>
                </div>
                <div v-if="sessions.length === 0" class="session-empty">{{ t('settings.noSessions') }}</div>
              </div>
              <div class="settings-form-note">{{ t('settings.sessionNote') }}</div>
            </div>

            <!-- 分享管理 -->
            <div v-if="tab === 'shareManage'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabShares') }}</h3>
              <div class="share-toolbar">
                <el-select v-model="shareFilterStatus" class="settings-el-select" popper-class="settings-select-popper">
                  <el-option value="" :label="t('settings.allStatus')" />
                  <el-option value="valid" :label="t('settings.statusValid')" />
                  <el-option value="expired" :label="t('settings.statusExpired')" />
                  <el-option value="orphaned" :label="t('settings.statusOrphaned')" />
                </el-select>
                <button class="settings-btn settings-btn-ghost" :disabled="invalidShares.length === 0" @click="handleClearInvalidShares">{{ t('settings.clearInvalid', { n: invalidShares.length }) }}</button>
                <button class="settings-btn settings-btn-danger" :disabled="selectedShareIds.length === 0" @click="handleDeleteSelectedShares">{{ t('settings.deleteSelected', { n: selectedShareIds.length }) }}</button>
              </div>
              <div v-if="sharesLoading" class="session-empty">{{ t('common.loading') }}</div>
              <div v-else class="share-list">
                <div v-for="s in filteredShares" :key="s.id" class="share-item" :class="{ invalid: s.status !== 'valid' }">
                  <input type="checkbox" class="share-check" :value="s.id" v-model="selectedShareIds" />
                  <div class="share-info">
                    <div class="share-title-line">
                      <span class="share-title">{{ s.title }}</span>
                      <span class="share-status" :class="s.status === 'valid' ? 'ok' : 'bad'">{{ shareStatusText(s.status) }}</span>
                    </div>
                    <div class="share-meta">
                      <span>{{ t('settings.createdAt', { date: s.createdAt || t('common.unknown') }) }}</span>
                      <span>{{ t('settings.expiresAt', { date: s.expiresAt || t('settings.permanent') }) }}</span>
                    </div>
                    <div class="share-link">{{ shareUrl(s) }}</div>
                  </div>
                  <div class="share-actions">
                    <button class="share-action-btn" :title="t('settings.copyLink')" @click="copyShareLink(s)">{{ t('common.copy') }}</button>
                    <button class="share-action-btn" :title="t('settings.openLink')" @click="openShareLink(s)">{{ t('common.open') }}</button>
                    <button class="share-action-btn danger" :title="t('settings.deleteShare')" @click="handleDeleteShare(s)">{{ t('common.delete') }}</button>
                  </div>
                </div>
                <div v-if="filteredShares.length === 0" class="session-empty">{{ t('settings.noShares') }}</div>
              </div>
              <div class="settings-form-note">{{ t('settings.shareNote') }}</div>
            </div>

            <!-- 数据管理 -->
            <div v-if="tab === 'dataManagement'" class="settings-panel">
              <h3 class="settings-panel-title">{{ t('settings.tabData') }}</h3>
              <div class="data-mgmt-rows">
                <div class="data-mgmt-row">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">{{ t('settings.exportAll') }}</div>
                    <div class="data-mgmt-desc">{{ t('settings.exportAllDesc', { n: chatStore.countValidChats() }) }}</div>
                  </div>
                  <el-select v-model="exportFormat" class="settings-el-select" popper-class="settings-select-popper">
                    <el-option value="txt" :label="t('settings.fmtTxt')" />
                    <el-option value="md" :label="t('settings.fmtMd')" />
                    <el-option value="json" :label="t('settings.fmtJson')" />
                  </el-select>
                  <button class="settings-btn settings-btn-primary" @click="confirmExport">{{ t('settings.export') }}</button>
                </div>
                <div class="data-mgmt-row">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">{{ t('settings.importJson') }}</div>
                    <div class="data-mgmt-desc">{{ t('settings.importJsonDesc') }}</div>
                  </div>
                  <button class="settings-btn settings-btn-primary" @click="importFileRef && importFileRef.click()">{{ t('settings.chooseFile') }}</button>
                  <input ref="importFileRef" type="file" accept=".json,application/json" style="display:none" @change="handleImportJson" />
                </div>
                <div class="data-mgmt-row data-mgmt-row-danger">
                  <div class="data-mgmt-info">
                    <div class="data-mgmt-title">{{ t('settings.deleteAll') }}</div>
                    <div class="data-mgmt-desc">{{ t('settings.deleteAllDesc') }}</div>
                  </div>
                  <button class="settings-btn settings-btn-danger" @click="confirmDeleteAll">{{ t('common.delete') }}</button>
                </div>
              </div>
              <div class="settings-form-note">{{ t('settings.dataNote') }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox, ElLoading } from 'element-plus'
import QRCode from 'qrcode'
import { useI18n } from 'vue-i18n'
import { setLocale, getLocale } from '@/i18n'
import {
  changePassword, getSessions, kickSession, getTwoFactorStatus, setupTwoFactor,
  enableTwoFactor, disableTwoFactor, regenerateRecoveryCodes
} from '@/api/auth'
import { getPromptPresets, savePromptPresets } from '@/api/user'
import { getMyShares, deleteShare, batchDeleteMyShares } from '@/api/share'
import { useChatStore } from '@/stores/chat'

const emit = defineEmits(['close', 'logout'])
const { t } = useI18n()
const chatStore = useChatStore()
const modalContainer = ref(null)
const settingsContent = ref(null)

// 默认展示"通用"页（个人设置弹窗打开后默认进入通用设置，而非修改密码）
const tab = ref('general')

// 通用：界面语言下拉（切换即时生效并持久化，见 @/i18n 的 setLocale）
const uiLocale = ref(getLocale())

// 窄屏检测：≤480px 时导航改为顶部横排 Tab（与样式媒体查询断点一致）
const mobileQuery = window.matchMedia('(max-width: 480px)')
const isMobile = ref(mobileQuery.matches)
const onMobileChange = e => { isMobile.value = e.matches }
onMounted(() => {
  mobileQuery.addEventListener('change', onMobileChange)
  nextTick(() => modalContainer.value?.focus())
})
onBeforeUnmount(() => mobileQuery.removeEventListener('change', onMobileChange))

// 切换 Tab 时先重置内容滚动位置，再按需加载对应数据
async function onTabChange(name) {
  await nextTick()
  if (settingsContent.value) settingsContent.value.scrollTop = 0
  if (name === 'systemPrompt') loadPresets()
  else if (name === 'loginDevices') loadSessions()
  else if (name === 'shareManage') loadShares()
  else if (name === 'twoFactor') loadTwoFactorStatus()
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

// 双重验证状态与分步表单
const twoFactorLoading = ref(false)
const twoFactorActionLoading = ref(false)
const twoFactorStatus = ref({ enabled: false, recoveryCodesRemaining: 0 })
const setupPassword = ref('')
const twoFactorSetup = ref({ setupToken: '', secret: '', provisioningUri: '' })
const twoFactorQr = ref('')
const twoFactorSetupCode = ref('')
const setupCodeInput = ref(null)
const recoveryCodes = ref([])
const recoveryCodesSaved = ref(false)
const disableTwoFactorPassword = ref('')
const regenerateForm = ref({ password: '', code: '' })
const twoFactorTip = ref('')
const twoFactorTipError = ref(false)

// 分享管理
const shares = ref([])
const sharesLoading = ref(false)
const shareFilterStatus = ref('')
const selectedShareIds = ref([])

// 导出格式（txt/md/json）
const exportFormat = ref('txt')

// 读取 2FA 状态；接口只返回开关与剩余恢复码数量，不返回敏感凭据
async function loadTwoFactorStatus() {
  twoFactorLoading.value = true
  twoFactorTip.value = ''
  try {
    const data = await getTwoFactorStatus()
    if (data?.success) {
      twoFactorStatus.value = {
        enabled: !!data.enabled,
        recoveryCodesRemaining: Number(data.recoveryCodesRemaining || 0)
      }
    } else {
      twoFactorTip.value = data?.message || t('settings.requestFailed')
      twoFactorTipError.value = true
    }
  } catch (e) {
    twoFactorTip.value = t('settings.requestFailed')
    twoFactorTipError.value = true
  } finally {
    twoFactorLoading.value = false
  }
}

// 验证当前密码并在浏览器本地生成扫码二维码
async function startTwoFactorSetup() {
  twoFactorTip.value = ''
  twoFactorTipError.value = false
  if (!setupPassword.value) {
    twoFactorTip.value = t('settings.enterCurrentPassword')
    twoFactorTipError.value = true
    return
  }
  twoFactorActionLoading.value = true
  try {
    const data = await setupTwoFactor({ password: setupPassword.value })
    if (!data?.success) {
      twoFactorTip.value = data?.message || t('settings.requestFailed')
      twoFactorTipError.value = true
      return
    }
    twoFactorSetup.value = {
      setupToken: data.setupToken,
      secret: data.secret,
      provisioningUri: data.provisioningUri
    }
    twoFactorQr.value = await QRCode.toDataURL(data.provisioningUri, {
      width: 220,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: '#111111', light: '#ffffff' }
    })
    setupPassword.value = ''
    await nextTick()
    setupCodeInput.value?.focus()
  } catch (e) {
    twoFactorTip.value = t('settings.requestFailed')
    twoFactorTipError.value = true
  } finally {
    twoFactorActionLoading.value = false
  }
}

// TOTP 输入仅保留六位数字
function normalizeSetupCode() {
  twoFactorSetupCode.value = twoFactorSetupCode.value.replace(/\D/g, '').slice(0, 6)
  twoFactorTip.value = ''
}

// 提交身份验证器中的首次验证码并正式启用 2FA
async function confirmEnableTwoFactor() {
  if (!/^\d{6}$/.test(twoFactorSetupCode.value)) {
    twoFactorTip.value = t('settings.enterSixDigitCode')
    twoFactorTipError.value = true
    return
  }
  twoFactorActionLoading.value = true
  try {
    const data = await enableTwoFactor({
      setupToken: twoFactorSetup.value.setupToken,
      code: twoFactorSetupCode.value
    })
    if (!data?.success) {
      twoFactorTip.value = data?.message || t('settings.requestFailed')
      twoFactorTipError.value = true
      return
    }
    recoveryCodes.value = data.recoveryCodes || []
    recoveryCodesSaved.value = false
    twoFactorStatus.value = { enabled: true, recoveryCodesRemaining: recoveryCodes.value.length }
    cancelTwoFactorSetup(false)
    ElMessage.success(t('settings.twoFactorEnabled'))
  } catch (e) {
    twoFactorTip.value = t('settings.requestFailed')
    twoFactorTipError.value = true
  } finally {
    twoFactorActionLoading.value = false
  }
}

// 清除仍在内存中的扫码绑定数据；服务端挑战会在短期 TTL 后自动失效
function cancelTwoFactorSetup(clearTip = true) {
  twoFactorSetup.value = { setupToken: '', secret: '', provisioningUri: '' }
  twoFactorQr.value = ''
  twoFactorSetupCode.value = ''
  if (clearTip) twoFactorTip.value = ''
}

// 复制全部恢复码，失败时给出明确提示
async function copyRecoveryCodes() {
  try {
    await navigator.clipboard.writeText(recoveryCodes.value.join('\n'))
    ElMessage.success(t('settings.recoveryCodesCopied'))
  } catch (e) {
    ElMessage.error(t('settings.copyRecoveryCodesFailed'))
  }
}

// 将恢复码下载为本地文本文件，便于离线保管
function downloadRecoveryCodes() {
  const content = `${t('settings.recoveryCodesFileTitle')}\n\n${recoveryCodes.value.join('\n')}\n`
  const url = URL.createObjectURL(new Blob([content], { type: 'text/plain;charset=utf-8' }))
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'chatai-recovery-codes.txt'
  anchor.click()
  URL.revokeObjectURL(url)
}

// 用户确认已保存后隐藏仅展示一次的恢复码明文
function finishRecoveryCodes() {
  if (!recoveryCodesSaved.value) return
  recoveryCodes.value = []
  recoveryCodesSaved.value = false
  loadTwoFactorStatus()
}

// 二次确认并验证当前密码后关闭 2FA
async function handleDisableTwoFactor() {
  if (!disableTwoFactorPassword.value) {
    twoFactorTip.value = t('settings.enterCurrentPassword')
    twoFactorTipError.value = true
    return
  }
  try {
    await ElMessageBox.confirm(t('settings.disableTwoFactorConfirm'), t('settings.disableTwoFactor'), {
      confirmButtonText: t('settings.disableTwoFactor'),
      cancelButtonText: t('common.cancel'),
      type: 'warning'
    })
  } catch (e) {
    return
  }
  twoFactorActionLoading.value = true
  try {
    const data = await disableTwoFactor({ password: disableTwoFactorPassword.value })
    if (!data?.success) {
      twoFactorTip.value = data?.message || t('settings.opFailed')
      twoFactorTipError.value = true
      return
    }
    disableTwoFactorPassword.value = ''
    twoFactorStatus.value = { enabled: false, recoveryCodesRemaining: 0 }
    twoFactorTip.value = ''
    ElMessage.success(t('settings.twoFactorDisabled'))
  } catch (e) {
    twoFactorTip.value = t('settings.requestFailed')
    twoFactorTipError.value = true
  } finally {
    twoFactorActionLoading.value = false
  }
}

// 验证密码和当前 TOTP 后替换全部恢复码
async function handleRegenerateRecoveryCodes() {
  const { password, code } = regenerateForm.value
  if (!password || !/^\d{6}$/.test(code)) {
    twoFactorTip.value = t('settings.regenerateIncomplete')
    twoFactorTipError.value = true
    return
  }
  twoFactorActionLoading.value = true
  try {
    const data = await regenerateRecoveryCodes({ password, code })
    if (!data?.success) {
      twoFactorTip.value = data?.message || t('settings.opFailed')
      twoFactorTipError.value = true
      return
    }
    regenerateForm.value = { password: '', code: '' }
    recoveryCodes.value = data.recoveryCodes || []
    recoveryCodesSaved.value = false
    twoFactorTip.value = ''
  } catch (e) {
    twoFactorTip.value = t('settings.requestFailed')
    twoFactorTipError.value = true
  } finally {
    twoFactorActionLoading.value = false
  }
}

// 关闭设置窗口前保护尚未确认保存的恢复码，避免用户误丢失
async function requestClose() {
  if (recoveryCodes.value.length && !recoveryCodesSaved.value) {
    try {
      await ElMessageBox.confirm(t('settings.unsavedRecoveryCodesConfirm'), t('settings.unsavedRecoveryCodesTitle'), {
        confirmButtonText: t('settings.closeAnyway'),
        cancelButtonText: t('settings.continueSaving'),
        type: 'warning'
      })
    } catch (e) {
      return
    }
  }
  emit('close')
}

// 将 Tab 焦点限制在设置对话框内，避免键盘用户误操作背后的聊天页面
function trapModalFocus(event) {
  const root = modalContainer.value
  if (!root) return
  const focusable = [...root.querySelectorAll(
    'button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
  )].filter(element => element.offsetParent !== null)
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

async function submitChangePassword() {
  pwdTip.value = ''
  pwdTipError.value = false
  const { oldPwd, newPwd, confirmPwd } = pwdForm.value
  if (!oldPwd || !newPwd || !confirmPwd) {
    pwdTip.value = t('settings.pwdIncomplete')
    pwdTipError.value = true
    return
  }
  if (newPwd.length < 4) {
    pwdTip.value = t('settings.pwdTooShort')
    pwdTipError.value = true
    return
  }
  if (newPwd !== confirmPwd) {
    pwdTip.value = t('settings.pwdMismatch')
    pwdTipError.value = true
    return
  }
  if (oldPwd === newPwd) {
    pwdTip.value = t('settings.pwdSameAsOld')
    pwdTipError.value = true
    return
  }
  try {
    const data = await changePassword({ oldPassword: oldPwd, newPassword: newPwd, confirmPassword: confirmPwd })
    if (data.success) {
      ElMessage.success(t('settings.pwdChanged'))
      setTimeout(() => {
        emit('close')
        emit('logout')
      }, 1200)
    } else {
      pwdTip.value = data.message || t('settings.changeFailed')
      pwdTipError.value = true
    }
  } catch (e) {
    pwdTip.value = t('settings.requestFailed')
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
    promptTip.value = t('settings.presetTooMany')
    promptTipError.value = true
    return
  }
  if (presets.value.some(p => (p.content || '').length > 20000)) {
    promptTip.value = t('settings.presetTooLong')
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
      promptTip.value = t('settings.presetSavedTip')
      promptTipError.value = false
      ElMessage.success(t('settings.presetSaved'))
    } else {
      promptTip.value = data.message || t('settings.saveFailed')
      promptTipError.value = true
    }
  } catch (e) {
    promptTip.value = t('settings.saveFailed')
    promptTipError.value = true
  }
}

// 导出格式展示名随语言切换，改为函数式取字典
function exportFormatText(fmt) {
  const map = { txt: t('settings.fmtTxt'), md: t('settings.fmtMd'), json: t('settings.fmtJson') }
  return map[fmt] || map.txt
}

function confirmExport() {
  const count = chatStore.countValidChats()
  if (count === 0) {
    ElMessage.info(t('settings.noExportable'))
    return
  }
  const fmt = exportFormatText(exportFormat.value)
  ElMessageBox.confirm(t('settings.exportConfirm', { n: count, fmt }), t('settings.exportConfirmTitle'), {
    confirmButtonText: t('settings.export'),
    cancelButtonText: t('common.cancel')
  }).then(async () => {
    // 懒加载模式下导出前需先补齐未加载的会话正文，会话多时耗时较长，全屏 loading 提示进度
    const loading = ElLoading.service({
      lock: true,
      text: t('settings.exporting', { n: count }),
      background: 'rgba(0, 0, 0, 0.5)'
    })
    try {
      await chatStore.exportChats(exportFormat.value)
      ElMessage.success(t('settings.exported'))
    } catch (e) {
      ElMessage.error(t('settings.exportFailed'))
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
      ElMessage.success(t('settings.importDone', { n: imported }) + (skipped > 0 ? t('settings.importSkipped', { n: skipped }) : ''))
    } else {
      ElMessage.info(skipped > 0 ? t('settings.importNone', { n: skipped }) : t('settings.importEmpty'))
    }
  } catch (err) {
    ElMessage.error(t('settings.importFailed', { msg: err.message || t('settings.invalidBackup') }))
  }
}

function confirmDeleteAll() {
  const count = chatStore.countValidChats()
  if (count === 0) {
    ElMessage.info(t('settings.noDeletable'))
    return
  }
  ElMessageBox.confirm(t('settings.deleteAllConfirm', { n: count }), t('settings.deleteAll'), {
    confirmButtonText: t('common.delete'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }).then(() => {
    chatStore.deleteAllChats()
    ElMessage.success(t('settings.allDeleted'))
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
  ElMessageBox.confirm(t('settings.kickConfirm', { browser: s.browser || t('settings.unknownBrowser'), ip: s.ip || t('common.unknown') }), t('settings.kickTitle'), {
    confirmButtonText: t('settings.kick'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }).then(async () => {
    try {
      const data = await kickSession(s.sessionId)
      if (data.success) {
        ElMessage.success(t('settings.kicked'))
      } else {
        ElMessage.error(data.message || t('settings.opFailed'))
      }
    } catch (e) { /* 拦截器已提示 */ }
    loadSessions()
  }).catch(() => {})
}

// ===== 分享管理（仅管理当前账号自己的分享，功能口径与后台分享管理一致） =====

// 分享状态展示名随语言切换，改为函数式取字典
function shareStatusText(s) {
  const map = { valid: t('settings.statusValid'), expired: t('settings.statusExpired'), orphaned: t('settings.statusOrphaned') }
  return map[s] || s
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
    ElMessage.success(t('settings.linkCopied'))
  } catch (e) {
    // 非 https 环境剪贴板可能不可用，降级弹窗展示
    ElMessageBox.alert(shareUrl(s), t('settings.copyManually'), { confirmButtonText: t('common.gotIt') })
  }
}

function openShareLink(s) {
  window.open(shareUrl(s), '_blank')
}

// 单条删除（撤销分享）
async function handleDeleteShare(s) {
  try {
    await ElMessageBox.confirm(t('settings.deleteShareConfirm', { title: s.title }), t('settings.deleteConfirmTitle'), { type: 'warning' })
    const res = await deleteShare(s.id)
    if (res?.success) {
      ElMessage.success(t('settings.deleted'))
      await loadShares()
    } else {
      ElMessage.error(res?.message || t('settings.deleteFailed'))
    }
  } catch { /* cancelled */ }
}

// 批量删除选中
async function handleDeleteSelectedShares() {
  const ids = selectedShareIds.value
  if (ids.length === 0) return
  try {
    await ElMessageBox.confirm(t('settings.batchDeleteConfirm', { n: ids.length }), t('settings.batchDeleteTitle'), { type: 'warning' })
    await doBatchDeleteShares(ids)
  } catch { /* cancelled */ }
}

// 一键清除失效（已过期 + 源会话已删）
async function handleClearInvalidShares() {
  const rows = invalidShares.value
  if (rows.length === 0) return
  try {
    await ElMessageBox.confirm(
      t('settings.clearInvalidConfirm', { n: rows.length }),
      t('settings.clearInvalidTitle'), { type: 'warning' }
    )
    await doBatchDeleteShares(rows.map(r => r.id))
  } catch { /* cancelled */ }
}

async function doBatchDeleteShares(ids) {
  const res = await batchDeleteMyShares(ids)
  if (res?.success) {
    ElMessage.success(t('settings.batchDeleted', { n: res.deleted ?? ids.length }))
    await loadShares()
  } else {
    ElMessage.error(res?.message || t('settings.deleteFailed'))
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
  height: min(680px, 80vh);
  max-height: none;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
@supports (height: 100dvh) {
  .modal-container {
    height: min(680px, 80dvh);
  }
}
.modal-header {
  display: flex;
  flex: 0 0 auto;
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
  min-height: 0;
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
  min-width: 0;
  min-height: 0;
  padding: 20px;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
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
/* === el-select 下拉（通用/分享管理/数据管理共用）：触发器主题适配，浮层见 chat.css 的 .settings-select-popper === */
.settings-el-select {
  width: 150px;
  flex-shrink: 0;
}
/* EP 用 box-shadow 模拟边框，默认/hover/focus 三态需合并写并加 !important */
.settings-el-select :deep(.el-select__wrapper),
.settings-el-select :deep(.el-select__wrapper:hover),
.settings-el-select :deep(.el-select__wrapper.is-focused) {
  background: var(--paper, #252536) !important;
  box-shadow: 0 0 0 1px var(--border, #333) inset !important;
  border-radius: 6px;
  min-height: 34px;
  padding: 0 12px;
  cursor: pointer;
}
.settings-el-select :deep(.el-select__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--ink-4, #666) inset !important;
}
.settings-el-select :deep(.el-select__wrapper.is-focused) {
  box-shadow: 0 0 0 1px var(--primary, #6366f1) inset !important;
}
.settings-el-select :deep(.el-select__selected-item) {
  color: var(--ink, #eee);
  font-size: 13px;
}
.settings-el-select :deep(.el-select__placeholder) {
  color: var(--ink-3, #999);
  font-size: 13px;
}
.settings-el-select :deep(.el-select__suffix) {
  color: var(--ink-3, #999);
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

/* === 双重验证：状态、扫码和恢复码 === */
.security-status {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 14px;
  margin-bottom: 16px;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
}
.security-status-dot {
  width: 9px;
  height: 9px;
  margin-top: 5px;
  flex-shrink: 0;
  border-radius: 50%;
  background: var(--ink-4, #777);
}
.security-status.enabled {
  border-color: color-mix(in srgb, #22c55e 52%, var(--border, #333));
}
.security-status.enabled .security-status-dot {
  background: #22c55e;
}
.security-status strong,
.security-section h4 {
  color: var(--ink, #eee);
  font-size: 13px;
}
.security-status p,
.security-section p {
  margin: 4px 0 0;
  color: var(--ink-3, #999);
  font-size: 11px;
  line-height: 1.6;
}
.two-factor-intro {
  margin: 0 0 14px;
}
.qr-setup-layout {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 18px;
}
.qr-frame {
  width: 170px;
  height: 170px;
  padding: 8px;
  flex-shrink: 0;
  border-radius: 8px;
  background: #fff;
}
.qr-frame img {
  display: block;
  width: 100%;
  height: 100%;
}
.manual-secret {
  min-width: 0;
  color: var(--ink-3, #999);
  font-size: 11px;
}
.manual-secret code {
  display: block;
  margin-top: 8px;
  color: var(--ink, #eee);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
  user-select: all;
}
.otp-input {
  max-width: 220px;
  letter-spacing: 0.14em;
  font-variant-numeric: tabular-nums;
}
.inline-actions,
.recovery-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.recovery-code-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px 12px;
  padding: 14px;
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  background: var(--paper, #252536);
}
.recovery-code-grid code {
  color: var(--ink, #eee);
  font-size: 12px;
  letter-spacing: 0.04em;
  user-select: all;
}
.recovery-confirm {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 16px 0;
  color: var(--ink-2, #ccc);
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
}
.recovery-confirm input {
  margin-top: 2px;
  accent-color: var(--primary, #6366f1);
}
.security-section {
  padding: 16px 0;
  border-top: 1px solid var(--border, #333);
}
.security-section h4 {
  margin: 0;
}
.security-section p {
  margin-bottom: 12px;
}
.danger-zone {
  margin-top: 4px;
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
    height: 90vh;
    max-height: none;
  }
  @supports (height: 100dvh) {
    .modal-container {
      height: 90dvh;
    }
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
  .qr-setup-layout {
    align-items: flex-start;
    flex-direction: column;
  }
  .recovery-code-grid {
    grid-template-columns: 1fr;
  }
}
</style>
