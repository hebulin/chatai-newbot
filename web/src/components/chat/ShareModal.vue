<template>
  <Teleport to="body">
    <div class="modal-overlay" @click.self="$emit('close')">
      <div class="share-modal" role="dialog" aria-modal="true" :aria-label="t('chat.shareTitle')">
        <!-- 标题栏 -->
        <div class="share-modal-header">
          <div class="share-modal-title">{{ t('chat.shareTitle') }}</div>
          <button class="share-modal-close" @click="$emit('close')" :aria-label="t('common.close')">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>

        <!-- 表单区：结果态隐藏 -->
        <div v-if="!resultUrl" class="share-modal-body">
          <div class="share-form-item">
            <label class="share-form-label" for="share-expire-days">{{ t('chat.shareExpireDays') }}</label>
            <input
              id="share-expire-days"
              v-model="form.expireDays"
              class="share-form-input"
              type="number"
              min="0"
              inputmode="numeric"
              :placeholder="t('chat.shareExpireDaysPlaceholder')"
            />
            <div class="share-form-hint">{{ t('chat.shareExpireDaysHint') }}</div>
          </div>

          <div class="share-form-item">
            <label class="share-form-label" for="share-password">{{ t('chat.sharePassword') }}</label>
            <input
              id="share-password"
              v-model="form.password"
              class="share-form-input"
              type="password"
              maxlength="100"
              :placeholder="t('chat.sharePasswordPlaceholder')"
            />
            <div class="share-form-hint">{{ t('chat.sharePasswordHint') }}</div>
          </div>

          <div class="share-form-item">
            <label class="share-form-label" for="share-max-views">{{ t('chat.shareMaxViews') }}</label>
            <input
              id="share-max-views"
              v-model="form.maxViews"
              class="share-form-input"
              type="number"
              min="0"
              inputmode="numeric"
              :placeholder="t('chat.shareMaxViewsPlaceholder')"
            />
            <div class="share-form-hint">{{ t('chat.shareMaxViewsHint') }}</div>
          </div>

          <div class="share-form-item">
            <label class="share-form-label">{{ t('chat.shareSanitized') }}</label>
            <label class="share-switch-row">
              <input v-model="form.sanitized" type="checkbox" class="share-switch-input" />
              <span class="share-switch-slider" aria-hidden="true"></span>
              <span class="share-switch-text">{{ form.sanitized ? t('chat.shareSanitizedOn') : t('chat.shareSanitizedOff') }}</span>
            </label>
            <div class="share-form-hint">{{ t('chat.shareSanitizedHint') }}</div>
          </div>

          <div v-if="errorMsg" class="share-form-error" role="alert">{{ errorMsg }}</div>
        </div>

        <!-- 结果区：分享链接生成后展示 -->
        <div v-else class="share-modal-body">
          <div class="share-result-tip">{{ resultCopied ? t('chat.shareCreatedCopied') : t('chat.shareCreated') }}</div>
          <div class="share-result-url">{{ resultUrl }}</div>
          <div class="share-result-meta">{{ resultExpiry }}</div>
        </div>

        <!-- 底部按钮 -->
        <div class="share-modal-footer">
          <template v-if="!resultUrl">
            <button class="share-btn share-btn-ghost" @click="$emit('close')">{{ t('common.cancel') }}</button>
            <button class="share-btn share-btn-primary" :disabled="submitting" @click="handleSubmit">
              {{ submitting ? t('common.loading') : t('chat.genLink') }}
            </button>
          </template>
          <template v-else>
            <button class="share-btn share-btn-ghost" @click="$emit('close')">{{ t('common.close') }}</button>
            <button class="share-btn share-btn-primary" @click="copyResult">{{ t('common.copy') }}</button>
          </template>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { saveChatHistory } from '@/api/chat'
import { createShare } from '@/api/share'
import { useChatStore } from '@/stores/chat'

// props：待分享的会话 ID
const props = defineProps({
  chatId: { type: String, required: true }
})
defineEmits(['close'])

const { t } = useI18n()
const chatStore = useChatStore()

// 表单状态（字符串输入，提交时再解析为数值）
const form = reactive({
  expireDays: '0',
  password: '',
  maxViews: '0',
  sanitized: false
})
const submitting = ref(false)
const errorMsg = ref('')

// 结果状态
const resultUrl = ref('')
const resultExpiry = ref('')
const resultCopied = ref(false)

/**
 * 校验表单并解析数值
 * @returns {{expireDays:number,password:string,maxViews:number,sanitized:boolean}|null} 合法返回载荷，非法返回 null 并设置 errorMsg
 */
function validateForm() {
  const daysStr = String(form.expireDays ?? '').trim()
  const viewsStr = String(form.maxViews ?? '').trim()
  if (daysStr !== '' && !/^\d+$/.test(daysStr)) {
    errorMsg.value = t('chat.nonNegInt')
    return null
  }
  if (viewsStr !== '' && !/^\d+$/.test(viewsStr)) {
    errorMsg.value = t('chat.nonNegInt')
    return null
  }
  const pwd = String(form.password || '').trim()
  if (pwd.length > 100) {
    errorMsg.value = t('chat.sharePasswordTooLong')
    return null
  }
  errorMsg.value = ''
  return {
    expireDays: daysStr === '' ? 0 : parseInt(daysStr, 10),
    password: pwd,
    maxViews: viewsStr === '' ? 0 : Math.max(0, parseInt(viewsStr, 10) || 0),
    sanitized: !!form.sanitized
  }
}

/**
 * 提交创建分享：先强制同步会话到服务端，再生成只读分享链接
 */
async function handleSubmit() {
  if (submitting.value) return
  const payload = validateForm()
  if (!payload) return
  submitting.value = true
  try {
    // 绕过 500ms 防抖，确保服务端已持有最新会话
    await saveChatHistory({
      lastChatId: chatStore.currentChatId,
      chats: chatStore.chats,
      chatMeta: chatStore.chatMeta,
      deletedChatIds: chatStore.deletedChatIds
    })
    const res = await createShare(props.chatId, payload)
    if (res && res.success) {
      resultUrl.value = location.origin + '/share/' + res.data.id
      resultExpiry.value = res.data.expiresAt
        ? t('chat.expiryTip', { date: res.data.expiresAt })
        : t('chat.permanent')
      // 尝试自动复制到剪贴板（非 https 环境可能失败）
      try {
        await navigator.clipboard.writeText(resultUrl.value)
        resultCopied.value = true
      } catch (e) { /* 剪贴板不可用时用户可手动复制 */ }
    } else {
      errorMsg.value = (res && res.message) || t('chat.shareFailed')
    }
  } catch (e) {
    // 网络/接口异常提示已由 request 拦截器统一处理
  } finally {
    submitting.value = false
  }
}

/**
 * 手动复制结果链接
 */
async function copyResult() {
  try {
    await navigator.clipboard.writeText(resultUrl.value)
    resultCopied.value = true
    ElMessage.success(t('chat.shareCreatedCopied'))
  } catch (e) {
    ElMessage.error(t('chat.shareFailed'))
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

/* 脱敏开关 */
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

/* 结果态 */
.share-result-tip {
  font-size: 13.5px;
  color: var(--ink, #eee);
  margin-bottom: 10px;
}
.share-result-url {
  word-break: break-all;
  font-size: 13px;
  color: var(--primary, #6c7bff);
  background: var(--paper, #262637);
  border: 1px solid var(--border, #333);
  border-radius: 8px;
  padding: 10px 12px;
  line-height: 1.6;
}
.share-result-meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--ink-3, #999);
}

/* 底部按钮 */
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

/* 移动端适配：底部弹层 */
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
