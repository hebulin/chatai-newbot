<template>
  <!-- 回收站弹窗：软删除会话的查看/搜索/恢复/彻底删除/清空 -->
  <Teleport to="body">
    <div class="recycle-bin-overlay" @click.self="$emit('close')">
      <div class="recycle-bin-modal" role="dialog" aria-modal="true" :aria-label="t('trash.title')">
        <div class="recycle-bin-header">
          <span class="recycle-bin-title">{{ t('trash.title') }}</span>
          <button class="recycle-bin-close" @click="$emit('close')" :aria-label="t('common.close')">×</button>
        </div>
        <div class="recycle-bin-toolbar">
          <input
            v-model="keyword"
            class="recycle-bin-search"
            :placeholder="t('trash.searchPlaceholder')"
            autocomplete="off"
          />
          <label class="recycle-bin-select-all">
            <input type="checkbox" :checked="allChecked" :disabled="busy" @change="toggleSelectAll" />
            {{ t('trash.selectAll') }}
          </label>
        </div>
        <div class="recycle-bin-body">
          <div v-if="loading" class="recycle-bin-empty">{{ t('trash.loading') }}</div>
          <div v-else-if="filteredList.length === 0" class="recycle-bin-empty">{{ t('trash.empty') }}</div>
          <div v-for="item in filteredList" :key="item.id" class="recycle-bin-item">
            <input type="checkbox" :checked="selected.has(item.id)" :disabled="busy" @change="toggleSelect(item.id)" />
            <div class="recycle-bin-info">
              <div class="recycle-bin-name">{{ item.title }}</div>
              <div class="recycle-bin-meta">{{ t('trash.meta', { n: item.count, time: item.deletedAt || '' }) }}</div>
            </div>
            <button class="recycle-bin-btn" :disabled="busy" @click="restoreOne(item)">{{ t('trash.restore') }}</button>
            <button class="recycle-bin-btn danger" :disabled="busy" @click="purgeOne(item)">{{ t('trash.purge') }}</button>
          </div>
        </div>
        <div class="recycle-bin-footer">
          <button class="recycle-bin-btn" :disabled="selected.size === 0 || busy" @click="restoreSelected">
            {{ t('trash.restoreSelected', { n: selected.size }) }}
          </button>
          <button class="recycle-bin-btn danger" :disabled="selected.size === 0 || busy" @click="purgeSelected">
            {{ t('trash.purgeSelected', { n: selected.size }) }}
          </button>
          <button class="recycle-bin-btn danger" :disabled="list.length === 0 || busy" @click="emptyTrash">
            {{ t('trash.emptyAll') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { listTrash, purgeTrash } from '@/api/chat'
import { useChatStore } from '@/stores/chat'

const emit = defineEmits(['close', 'changed'])
const { t } = useI18n()
const chatStore = useChatStore()

const list = ref([])
const loading = ref(false)
const restoring = ref(false)
const purging = ref(false)
const keyword = ref('')
const selected = ref(new Set())

// 任一写操作进行中即锁定列表操作，避免恢复/彻底删除并发产生矛盾提示
const busy = computed(() => restoring.value || purging.value)

// 本地搜索过滤（标题/预览内容，忽略大小写）
const filteredList = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.value
  return list.value.filter(item =>
    (item.title || '').toLowerCase().includes(kw) ||
    (item.preview || '').toLowerCase().includes(kw))
})

const allChecked = computed(() =>
  filteredList.value.length > 0 && filteredList.value.every(item => selected.value.has(item.id)))

// 加载回收站列表
async function load() {
  loading.value = true
  try {
    const res = await listTrash()
    if (res?.success) {
      list.value = res.data || []
      selected.value = new Set()
    }
  } catch (e) {
    ElMessage.error(t('trash.loadFailed'))
  } finally {
    loading.value = false
  }
}

function toggleSelect(id) {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selected.value = next
}

function toggleSelectAll() {
  if (allChecked.value) {
    selected.value = new Set()
  } else {
    selected.value = new Set(filteredList.value.map(item => item.id))
  }
}

// 恢复单条会话
async function restoreOne(item) {
  await doRestore([item.id])
}

// 批量恢复选中的会话
async function restoreSelected() {
  await doRestore([...selected.value])
}

async function doRestore(ids) {
  // 恢复/彻底删除期间禁止重复提交，避免并发操作产生矛盾提示
  if (busy.value) return
  restoring.value = true
  try {
    // store 会等待首屏加载与本地同步空闲，并在恢复提交后自行强制对账；
    // 即使弹窗在请求期间被关闭，侧边栏仍会刷新。
    const res = await chatStore.restoreChatsFromTrash(ids)
    if (res?.success) {
      const restoredCount = (res.restored || []).length
      if (restoredCount > 0) ElMessage.success(t('trash.restored', { n: restoredCount }))
      else ElMessage.warning(t('trash.restoredNone'))
      // handled=true：store 已完成强制对账，父组件无需重复刷新
      emit('changed', { restored: res.restored || [], handled: true })
      await load()
    } else if (res?.busy) {
      // 历史仍在加载或本地保存尚未结束：恢复已中止，避免竞态
      ElMessage.warning(t('trash.restoreBusy'))
    } else if (res?.unknown) {
      // 请求结果未知（响应丢失）：store 已强制对账，提示用户稍后确认
      ElMessage.warning(t('trash.restoreUnknown'))
      emit('changed', { restored: [], handled: true })
      await load()
    } else {
      ElMessage.error(res?.message || t('trash.restoreFailed'))
      // store 已对失败/部分提交做过强制对账，这里只刷新回收站列表
      emit('changed', { restored: [], handled: true })
      await load()
    }
  } catch (e) {
    ElMessage.error(t('trash.restoreFailed'))
    // store 已对账；重新拉取回收站列表避免误导
    emit('changed', { restored: [], handled: true })
    await load()
  } finally {
    restoring.value = false
  }
}

// 彻底删除单条（二次确认，不可恢复）
async function purgeOne(item) {
  try {
    await ElMessageBox.confirm(t('trash.purgeConfirm', { title: item.title }), t('trash.purgeTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'error'
    })
  } catch { return }
  await doPurge([item.id])
}

// 批量彻底删除选中（二次确认）
async function purgeSelected() {
  try {
    await ElMessageBox.confirm(t('trash.purgeSelectedConfirm', { n: selected.value.size }), t('trash.purgeTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'error'
    })
  } catch { return }
  await doPurge([...selected.value])
}

// 清空回收站（明确确认，不可恢复）
async function emptyTrash() {
  try {
    await ElMessageBox.confirm(t('trash.emptyConfirm', { n: list.value.length }), t('trash.emptyTitle'), {
      confirmButtonText: t('common.confirm'),
      cancelButtonText: t('common.cancel'),
      type: 'error'
    })
  } catch { return }
  await doPurge([])
}

async function doPurge(ids) {
  // 恢复/彻底删除进行中禁止并发操作，避免同一会话出现互相矛盾的结果
  if (busy.value) return
  purging.value = true
  try {
    const res = await purgeTrash(ids)
    if (res?.success) {
      ElMessage.success(t('trash.purged', { n: res.purged || 0 }))
      emit('changed')
      await load()
    } else {
      ElMessage.error(res?.message || t('trash.purgeFailed'))
    }
  } catch (e) {
    ElMessage.error(t('trash.purgeFailed'))
  } finally {
    purging.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.recycle-bin-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.5);
  padding: 16px;
}
.recycle-bin-modal {
  width: min(560px, 92vw);
  max-height: 80dvh;
  display: flex;
  flex-direction: column;
  background: var(--bg-2, #1e1e2e);
  border: 1px solid var(--border, #333);
  border-radius: 14px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .3);
}
.recycle-bin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--border, rgba(128,128,128,.2));
}
.recycle-bin-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg, inherit);
}
.recycle-bin-close {
  border: none;
  background: transparent;
  color: var(--fg-3, #999);
  font-size: 20px;
  cursor: pointer;
  line-height: 1;
  padding: 2px 6px;
  border-radius: 6px;
}
.recycle-bin-close:hover {
  color: var(--fg, inherit);
  background: var(--bg, rgba(0,0,0,.06));
}
.recycle-bin-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--border, rgba(128,128,128,.2));
}
.recycle-bin-search {
  flex: 1;
  padding: 7px 12px;
  border-radius: 8px;
  border: 1px solid var(--border, rgba(128,128,128,.3));
  background: var(--bg-2, transparent);
  color: var(--fg, inherit);
  font-size: 13px;
  outline: none;
}
.recycle-bin-search:focus {
  border-color: var(--primary, #4285f4);
}
.recycle-bin-select-all {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12.5px;
  color: var(--fg-2, #666);
  white-space: nowrap;
  cursor: pointer;
}
.recycle-bin-body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 16px;
  min-height: 160px;
}
.recycle-bin-empty {
  text-align: center;
  color: var(--fg-3, #999);
  padding: 40px 0;
  font-size: 13px;
}
.recycle-bin-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 4px;
  border-bottom: 1px dashed var(--border, rgba(128,128,128,.15));
}
.recycle-bin-info {
  flex: 1;
  min-width: 0;
}
.recycle-bin-name {
  font-size: 13.5px;
  color: var(--fg, inherit);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.recycle-bin-meta {
  font-size: 11.5px;
  color: var(--fg-3, #999);
  margin-top: 2px;
}
.recycle-bin-btn {
  flex-shrink: 0;
  padding: 4px 12px;
  border-radius: 7px;
  border: 1px solid var(--border, rgba(128,128,128,.3));
  background: var(--bg-2, transparent);
  color: var(--fg-2, #666);
  font-size: 12px;
  cursor: pointer;
}
.recycle-bin-btn:hover:not(:disabled) {
  color: var(--fg, inherit);
  border-color: var(--fg-3, #999);
}
.recycle-bin-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.recycle-bin-btn.danger {
  color: #e5484d;
  border-color: rgba(229, 72, 77, .4);
}
.recycle-bin-btn.danger:hover:not(:disabled) {
  background: rgba(229, 72, 77, .1);
  color: #e5484d;
}
.recycle-bin-footer {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--border, rgba(128,128,128,.2));
  flex-wrap: wrap;
}
@media (max-width: 768px) {
  .recycle-bin-modal { width: 94vw; }
  .recycle-bin-footer { justify-content: stretch; }
  .recycle-bin-footer .recycle-bin-btn { flex: 1; }
}
</style>
