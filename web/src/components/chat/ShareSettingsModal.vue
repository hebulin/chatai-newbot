<template>
  <Teleport to="body">
    <div class="modal-overlay" @click.self="$emit('close')">
      <div class="share-modal" role="dialog" aria-modal="true" :aria-label="t('settings.shareEditTitle')">
        <!-- 标题栏 -->
        <div class="share-modal-header">
          <div class="share-modal-title">{{ t('settings.shareEditTitle') }}</div>
          <button class="share-modal-close" @click="$emit('close')" :aria-label="t('common.close')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>

        <div class="share-modal-body">
          <!-- 密码开关 -->
          <div class="share-form-item">
            <label class="share-form-label">{{ t('settings.sharePasswordEnabled') }}</label>
            <label class="share-switch-row">
              <input v-model="enabled" type="checkbox" class="share-switch-input" />
              <span class="share-switch-slider" aria-hidden="true"></span>
              <span class="share-switch-text">{{ enabled ? t('settings.sharePasswordEnabledOn') : t('settings.sharePasswordEnabledOff') }}</span>
            </label>
          </div>

          <!-- 密码输入（开启时必填） -->
          <div v-if="enabled" class="share-form-item">
            <label class="share-form-label" for="share-edit-password">{{ t('chat.sharePassword') }}</label>
            <input
              id="share-edit-password"
              v-model="password"
              class="share-form-input"
              type="text"
              maxlength="100"
              :placeholder="t('settings.shareEditPasswordPlaceholder')"
            />
          </div>

          <!-- 访问次数上限（0=不限） -->
          <div class="share-form-item">
            <label class="share-form-label" for="share-edit-max-views">{{ t('settings.shareEditMaxViews') }}</label>
            <input
              id="share-edit-max-views"
              v-model="maxViews"
              class="share-form-input"
              type="number"
              min="0"
              inputmode="numeric"
              :placeholder="t('settings.shareEditMaxViewsPlaceholder')"
            />
            <div class="share-form-hint">{{ t('settings.shareEditCurrentViews', { n: share.accessCount ?? 0 }) }}</div>
          </div>

          <!-- 清零已访问次数（可单独勾选，用于恢复已超限分享） -->
          <div class="share-form-item">
            <label class="share-switch-row">
              <input v-model="resetCount" type="checkbox" class="share-switch-input" />
              <span class="share-switch-slider" aria-hidden="true"></span>
              <span class="share-switch-text">{{ t('settings.shareResetCount') }}</span>
            </label>
          </div>

          <div v-if="errorMsg" class="share-form-error" role="alert">{{ errorMsg }}</div>
        </div>

        <div class="share-modal-footer">
          <button class="share-btn share-btn-ghost" @click="$emit('close')">{{ t('common.cancel') }}</button>
          <button class="share-btn share-btn-primary" :disabled="submitting" @click="handleSubmit">
            {{ submitting ? t('common.loading') : t('common.save') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { updateShareSettings, updateAdminShareSettings } from '@/api/share'

// props：待修改的分享行；admin=true 走管理员接口（可修改任何人的分享）
const props = defineProps({
  share: { type: Object, required: true },
  admin: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'saved'])

const { t } = useI18n()

// 表单初始值来自分享行（密码为后端解密后的明文，旧数据无密文时为空串）
const enabled = ref(!!props.share.passwordProtected)
const password = ref(props.share.password || '')
const maxViews = ref(String(props.share.maxViews ?? 0))
const resetCount = ref(false)
const submitting = ref(false)
const errorMsg = ref('')

/**
 * 校验并提交修改：开启密码时必须填写密码；上限须为非负整数
 */
async function handleSubmit() {
  if (submitting.value) return
  const pwd = String(password.value || '').trim()
  const viewsStr = String(maxViews.value ?? '').trim()
  if (enabled.value && !pwd) {
    errorMsg.value = t('settings.shareEditPasswordRequired')
    return
  }
  if (pwd.length > 100) {
    errorMsg.value = t('chat.sharePasswordTooLong')
    return
  }
  if (viewsStr !== '' && !/^\d+$/.test(viewsStr)) {
    errorMsg.value = t('chat.nonNegInt')
    return
  }
  errorMsg.value = ''
  submitting.value = true
  const payload = {
    password: enabled.value ? pwd : '',
    maxViews: viewsStr === '' ? 0 : parseInt(viewsStr, 10),
    resetAccessCount: resetCount.value
  }
  try {
    const res = props.admin
      ? await updateAdminShareSettings(props.share.id, payload)
      : await updateShareSettings(props.share.id, payload)
    if (res && res.success) {
      ElMessage.success(t('settings.shareEditSaved'))
      emit('saved')
      emit('close')
    } else {
      errorMsg.value = (res && res.message) || t('settings.saveFailed')
    }
  } catch (e) {
    // 网络/接口异常提示已由 request 拦截器统一处理
  } finally {
    submitting.value = false
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
  padding: 16px;
}
.share-modal {
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 14px;
  width: 420px;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.share-modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--border, #333);
  flex-shrink: 0;
}
.share-modal-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ink, #eee);
}
.share-modal-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--ink-3, #999);
  cursor: pointer;
}
.share-modal-close:hover {
  background: var(--paper-2, #2a2a3e);
  color: var(--ink, #eee);
}
.share-modal-body {
  padding: 16px 18px;
  overflow-y: auto;
  min-height: 0;
}
.share-form-item {
  margin-bottom: 14px;
}
.share-form-item:last-of-type {
  margin-bottom: 0;
}
.share-form-label {
  display: block;
  font-size: 12.5px;
  font-weight: 500;
  color: var(--ink-2, #ccc);
  margin-bottom: 6px;
}
.share-form-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  font-size: 13px;
  color: var(--ink, #eee);
  background: var(--paper, #262637);
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  outline: none;
}
.share-form-input:focus {
  border-color: var(--primary, #6c7bff);
}
.share-form-hint {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--ink-3, #999);
  line-height: 1.5;
}
.share-form-error {
  margin-top: 12px;
  font-size: 12.5px;
  color: var(--danger, #f26d6d);
}
.share-switch-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}
.share-switch-input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}
.share-switch-slider {
  position: relative;
  width: 34px;
  height: 19px;
  border-radius: 19px;
  background: var(--paper-2, #2a2a3e);
  border: 1px solid var(--border, #333);
  transition: background .18s;
  flex-shrink: 0;
}
.share-switch-slider::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 13px;
  height: 13px;
  border-radius: 50%;
  background: var(--ink-3, #999);
  transition: transform .18s, background .18s;
}
.share-switch-input:checked + .share-switch-slider {
  background: var(--primary, #6c7bff);
  border-color: var(--primary, #6c7bff);
}
.share-switch-input:checked + .share-switch-slider::after {
  transform: translateX(15px);
  background: #fff;
}
.share-switch-input:focus-visible + .share-switch-slider {
  outline: 2px solid var(--primary, #6c7bff);
  outline-offset: 2px;
}
.share-switch-text {
  font-size: 12.5px;
  color: var(--ink-2, #ccc);
}
.share-modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 12px 18px;
  border-top: 1px solid var(--border, #333);
  flex-shrink: 0;
}
.share-btn {
  padding: 7px 16px;
  font-size: 13px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid var(--border, #333);
}
.share-btn-ghost {
  background: transparent;
  color: var(--ink-2, #ccc);
}
.share-btn-ghost:hover {
  background: var(--paper-2, #2a2a3e);
}
.share-btn-primary {
  background: var(--primary, #6c7bff);
  border-color: var(--primary, #6c7bff);
  color: #fff;
}
.share-btn-primary:hover:not(:disabled) {
  filter: brightness(1.08);
}
.share-btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
@media (max-width: 768px) {
  .modal-overlay {
    align-items: flex-end;
    padding: 0;
  }
  .share-modal {
    width: 100%;
    border-radius: 14px 14px 0 0;
    max-height: 90dvh;
  }
}
</style>
