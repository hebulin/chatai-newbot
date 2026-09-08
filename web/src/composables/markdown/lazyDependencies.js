let mermaidPromise = null
let xlsxPromise = null

/**
 * 仅在页面真正存在 Mermaid 图表时加载核心运行时；各图表定义继续由 Mermaid 按类型动态加载。
 */
export function loadMermaidRuntime() {
  if (!mermaidPromise) {
    mermaidPromise = import('mermaid').then(module => module.default || module).catch(error => {
      mermaidPromise = null
      throw error
    })
  }
  return mermaidPromise
}

/**
 * 仅在用户选择 Excel 导出时加载 XLSX 运行时。
 */
export function loadXlsxExporter() {
  if (!xlsxPromise) {
    xlsxPromise = import('xlsx').catch(error => {
      xlsxPromise = null
      throw error
    })
  }
  return xlsxPromise
}
