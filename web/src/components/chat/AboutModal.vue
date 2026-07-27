<template>
  <Teleport to="body">
    <div class="modal-overlay" @click="$emit('close')">
      <div class="about-modal" @click.stop>
        <div class="about-logo"><img :src="logoSrc" alt="AI Chat" /></div>
        <div class="about-title">AI Chat Platform</div>
        <div class="about-version">软件版本号：v{{ APP_VERSION }}</div>
        <div class="about-desc">一个简洁高效的多模型 AI 聊天平台</div>
        <button class="about-close-btn" @click="$emit('close')">关闭</button>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { useTheme } from '@/composables/useTheme'
import { APP_VERSION } from '@/config/version'

defineEmits(['close'])

const { getTheme } = useTheme()
const logoSrc = computed(() => getTheme() === 'dark' ? '/icons/AIBot_ss.svg' : '/icons/AIBot.svg')
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
.about-modal {
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 16px;
  padding: 32px;
  text-align: center;
  width: 320px;
}
.about-logo img {
  width: 56px;
  height: 56px;
  border-radius: 12px;
}
.about-title {
  font-size: 18px;
  font-weight: 600;
  margin-top: 16px;
  color: var(--ink, #eee);
}
.about-version {
  font-size: 12px;
  color: var(--ink-3, #999);
  margin-top: 8px;
}
.about-desc {
  font-size: 13px;
  color: var(--ink-2, #ccc);
  margin-top: 12px;
}
.about-close-btn {
  margin-top: 20px;
  padding: 8px 24px;
  border: 1px solid var(--border, #333);
  border-radius: 6px;
  background: transparent;
  color: var(--ink-2, #ccc);
  cursor: pointer;
  font-size: 13px;
}
.about-close-btn:hover {
  background: var(--paper-2, #2a2a3e);
}
</style>
