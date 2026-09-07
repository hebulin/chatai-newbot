import { gzipSync } from 'node:zlib'

export const DEFAULT_BUNDLE_BUDGETS = [
  {
    name: '主入口 JavaScript',
    matches: chunk => chunk.type === 'chunk' && chunk.isEntry,
    rawBytes: 330 * 1024,
    gzipBytes: 118 * 1024
  },
  {
    name: '聊天页面 JavaScript',
    matches: chunk => chunk.type === 'chunk' && /[\\/]src[\\/]views[\\/]ChatView\.vue$/.test(chunk.facadeModuleId || ''),
    rawBytes: 325 * 1024,
    gzipBytes: 108 * 1024
  }
]

/** 只沿静态导入遍历依赖闭包，动态导入功能块不计入首屏。 */
export function collectStaticChunks(bundle, root) {
  const chunks = new Map()
  const pending = [root]
  while (pending.length) {
    const chunk = pending.pop()
    if (!chunk || chunk.type !== 'chunk' || chunks.has(chunk.fileName)) continue
    chunks.set(chunk.fileName, chunk)
    for (const fileName of chunk.imports || []) pending.push(bundle[fileName])
  }
  return [...chunks.values()]
}

/** 检查入口的整个静态依赖闭包，禁止重型图表和表格导出包被间接提前加载。 */
export function inspectLazyBoundaries(bundle, budgets = DEFAULT_BUNDLE_BUDGETS) {
  const violations = []
  const heavyModule = /\/node_modules\/(mermaid|elkjs|cytoscape|cytoscape-cose-bilkent|xlsx)\//
  for (const budget of budgets) {
    for (const root of Object.values(bundle).filter(budget.matches)) {
      for (const chunk of collectStaticChunks(bundle, root)) {
        const heavy = (chunk.moduleIds || Object.keys(chunk.modules || {}))
          .find(id => heavyModule.test(id.replace(/\\/g, '/')))
        if (heavy) violations.push(`${budget.name} 静态依赖 ${chunk.fileName} 提前包含按需功能：${heavy}`)
      }
    }
  }
  return violations
}

/**
 * 计算构建产物是否超过指定原始与 gzip 体积预算。
 */
export function inspectBundleBudgets(bundle, budgets = DEFAULT_BUNDLE_BUDGETS) {
  const outputs = Object.values(bundle)
  const violations = []
  for (const budget of budgets) {
    const matched = outputs.filter(budget.matches)
    if (matched.length === 0) {
      violations.push(`${budget.name}：没有匹配到构建产物`)
      continue
    }
    for (const output of matched) {
      const source = output.type === 'chunk' ? output.code : String(output.source || '')
      const rawBytes = Buffer.byteLength(source)
      const gzipBytes = gzipSync(source).byteLength
      if (rawBytes > budget.rawBytes || gzipBytes > budget.gzipBytes) {
        violations.push(
          `${budget.name} ${output.fileName} 超出预算：` +
          `raw ${(rawBytes / 1024).toFixed(1)} KiB / ${(budget.rawBytes / 1024).toFixed(1)} KiB，` +
          `gzip ${(gzipBytes / 1024).toFixed(1)} KiB / ${(budget.gzipBytes / 1024).toFixed(1)} KiB`
        )
      }
    }
  }
  return violations
}

/**
 * 创建 Vite 构建体积门禁插件，预算失败时直接中止生产构建。
 */
export function bundleBudgetPlugin(budgets = DEFAULT_BUNDLE_BUDGETS) {
  return {
    name: 'bundle-budget',
    apply: 'build',
    generateBundle(options, bundle) {
      const violations = [
        ...inspectBundleBudgets(bundle, budgets),
        ...inspectLazyBoundaries(bundle, budgets)
      ]
      if (violations.length > 0) this.error(`构建体积预算检查失败：\n${violations.join('\n')}`)
    }
  }
}
