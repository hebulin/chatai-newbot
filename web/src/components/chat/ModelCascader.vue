<template>
  <Teleport to="body">
    <div v-if="open" class="model-cascader-overlay" @click="open = false">
      <div class="model-cascader-panel" @click.stop>
        <div class="cascader-col cascader-col-providers">
          <div
            v-for="key in modelsStore.groupedModels.order"
            :key="key"
            class="cascader-item cascader-provider"
            :class="{ 'is-active': key === activeProvider }"
            @mouseenter="activeProvider = key"
            @click="activeProvider = key"
          >
            <span class="cascader-item-name">{{ modelsStore.groupedModels.groups[key].name }}</span>
            <svg class="cascader-item-arrow" width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5"><polyline points="9 18 15 12 9 6"/></svg>
          </div>
        </div>
        <div class="cascader-col cascader-col-models">
          <div
            v-for="key in modelsStore.groupedModels.order"
            :key="key"
            class="cascader-models-group"
            :class="{ 'is-active': key === activeProvider }"
          >
            <div
              v-for="m in modelsStore.groupedModels.groups[key].models"
              :key="m.id"
              class="cascader-item cascader-model"
              :class="{ 'is-selected': m.id === modelsStore.currentModelId }"
              @click="selectModel(m)"
            >
              <span class="cascader-item-name">{{ m.displayName }}</span>
              <svg class="cascader-check" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="20 6 9 17 4 12"/></svg>
            </div>
          </div>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref } from 'vue'
import { useModelsStore } from '@/stores/models'

const modelsStore = useModelsStore()
const open = ref(false)
const activeProvider = ref('')

function toggle() {
  open.value = !open.value
  if (open.value && modelsStore.groupedModels.order.length > 0) {
    const current = modelsStore.currentModel
    if (current) {
      activeProvider.value = current.providerName || current.providerId || modelsStore.groupedModels.order[0]
    } else {
      activeProvider.value = modelsStore.groupedModels.order[0]
    }
  }
}

function selectModel(model) {
  modelsStore.selectModel(model)
  open.value = false
}

defineExpose({ toggle, open })
</script>

<style scoped>
.model-cascader-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0,0,0,0.3);
}
.model-cascader-panel {
  display: flex;
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 12px;
  overflow: hidden;
  max-height: 70vh;
  box-shadow: 0 20px 60px rgba(0,0,0,0.4);
}
.cascader-col {
  min-width: 180px;
  max-height: 70vh;
  overflow-y: auto;
  padding: 8px;
}
.cascader-col-providers {
  border-right: 1px solid var(--border, #333);
}
.cascader-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--ink-2, #ccc);
  transition: background 0.15s;
}
.cascader-item:hover {
  background: var(--paper-2, #2a2a3e);
}
.cascader-provider.is-active {
  background: var(--paper-2, #2a2a3e);
  color: var(--primary, #6366f1);
}
.cascader-model.is-selected {
  color: var(--primary, #6366f1);
}
.cascader-model.is-selected .cascader-check {
  opacity: 1;
}
.cascader-check {
  margin-left: auto;
  opacity: 0;
}
.cascader-item-arrow {
  margin-left: auto;
  opacity: 0.4;
}
.cascader-models-group {
  display: none;
}
.cascader-models-group.is-active {
  display: block;
}
.cascader-item-name {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
