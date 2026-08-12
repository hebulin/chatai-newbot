<template>
  <!-- 后台统一分页组件（复用全站 .admin-pager 样式，格式：[条数/页] [上一页] 第X/Y页·共N条 [下一页]） -->
  <div class="admin-pager">
    <select :value="pageSize" class="admin-pager-size" @change="onSizeChange">
      <option v-for="opt in sizeOptions" :key="opt" :value="opt">{{ opt }}条/页</option>
    </select>
    <button class="admin-pager-btn" :disabled="page <= 1" @click="onPrev">上一页</button>
    <span class="admin-pager-info">第 {{ page }} / {{ totalPages }} 页 · 共 {{ total }} 条</span>
    <button class="admin-pager-btn" :disabled="page >= totalPages" @click="onNext">下一页</button>
  </div>
</template>

<script setup>
/**
 * 后台列表统一分页组件。
 * 通过 v-model:page / v-model:page-size 双向绑定页码与每页条数；
 * page-change 事件在翻页后触发（服务端分页页面据此重新拉取数据）；
 * size-change 事件在切换每页条数后触发（父组件据此重置页码并刷新数据）。
 */
const props = defineProps({
  /** 当前页码（从 1 开始） */
  page: { type: Number, required: true },
  /** 每页条数 */
  pageSize: { type: Number, required: true },
  /** 总记录数 */
  total: { type: Number, default: 0 },
  /** 总页数 */
  totalPages: { type: Number, default: 1 },
  /** 每页条数选项（默认 10/20/50，审计日志等页面可自定义） */
  sizeOptions: { type: Array, default: () => [10, 20, 50] }
})

const emit = defineEmits(['update:page', 'update:pageSize', 'page-change', 'size-change'])

/** 切换每页条数：更新绑定值并通知父组件重置页码/重新加载 */
function onSizeChange(e) {
  emit('update:pageSize', Number(e.target.value))
  emit('size-change')
}

/** 上一页：页码减一并通知父组件（父组件据此重新拉取数据） */
function onPrev() {
  if (props.page <= 1) return
  emit('update:page', props.page - 1)
  emit('page-change')
}

/** 下一页：页码加一并通知父组件 */
function onNext() {
  if (props.page >= props.totalPages) return
  emit('update:page', props.page + 1)
  emit('page-change')
}
</script>
