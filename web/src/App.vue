<template>
  <!-- el-config-provider 统一提升 Element Plus 弹出层基础 z-index 至 4000，
       使日期选择器(el-date-picker)、下拉(el-select)等组件树内弹出层的面板
       z-index（4001 起）高于自定义模态框遮罩（z-index:3000），
       避免弹出面板被模态框盖在下方；同时低于图片灯箱（z-index:5000）。 -->
  <el-config-provider :z-index="4000" :locale="zhCn">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import { onMounted } from 'vue'
// 按需引入后不再全局 app.use(ElementPlus)，中文语言包改由 el-config-provider 下发
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useTheme } from '@/composables/useTheme'

const { initTheme } = useTheme()

onMounted(() => {
  initTheme()
})
</script>

<style>
/* 命令式弹窗（ElMessage / ElMessageBox）通过 append-to-body 挂载到 body，
   脱离 <el-config-provider> 组件树，无法读取其 z-index 配置，仍用默认值（2001 起），
   会被自定义模态框（z-index:3000）遮挡。此处兜底提升其层级至 4500：
   高于自定义模态框(3000)与组件弹出层(4001+)、低于图片灯箱(5000)。
   注意：ElMessageBox 的真实 z-index 落在外层遮罩 .el-overlay.is-message-box 上（内层
   .el-overlay-message-box 不带 z-index），故必须提升外层遮罩层级才能盖住自定义模态框。 */
.el-overlay.is-message-box,
.el-message {
  z-index: 4500 !important;
}
</style>
