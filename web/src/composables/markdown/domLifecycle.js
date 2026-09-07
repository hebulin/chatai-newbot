/** 节点被 v-html 替换或组件卸载时释放外部资源；返回取消观察函数。 */
export function onElementDetached(element, cleanup) {
  const observer = new MutationObserver(() => {
    if (!element.isConnected) {
      observer.disconnect()
      cleanup()
    }
  })
  observer.observe(element.ownerDocument.documentElement, { childList: true, subtree: true })
  return () => observer.disconnect()
}
