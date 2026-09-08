import { ref } from 'vue'

/** Markdown 异步渲染依赖的响应式版本号。 */
export const renderDepsVersion = ref(0)

/** 通知消息组件异步渲染依赖已经就绪。 */
export function markRenderDependencyReady() {
  renderDepsVersion.value++
}
