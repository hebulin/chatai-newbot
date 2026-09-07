import { describe, expect, it } from 'vitest'
import { inspectBundleBudgets, inspectLazyBoundaries } from '../build/bundleBudget.js'

/** 创建构建体积测试使用的最小 Rollup chunk。 */
function chunk(fileName, code, options = {}) {
  return {
    type: 'chunk',
    fileName,
    code,
    isEntry: !!options.isEntry,
    facadeModuleId: options.facadeModuleId || null
  }
}

describe('构建体积预算', () => {
  it('沿静态依赖识别间接提前加载的重包，允许动态导入', () => {
    const main = chunk('index.js', '', { isEntry: true })
    const shared = { ...chunk('shared.js', ''), imports: ['elk.js'] }
    const elk = { ...chunk('elk.js', ''), moduleIds: ['D:\\web\\node_modules\\elkjs\\lib\\elk.bundled.js'] }
    const bundle = { 'index.js': main, 'shared.js': shared, 'elk.js': elk }
    main.dynamicImports = ['elk.js']
    expect(inspectLazyBoundaries(bundle)).toEqual([])
    main.imports = ['shared.js']
    expect(inspectLazyBoundaries(bundle)[0]).toContain('提前包含按需功能')
  })
  it('主入口与聊天页面均在预算内时通过', () => {
    const bundle = {
      main: chunk('assets/index.js', 'const main=1', { isEntry: true }),
      chat: chunk('assets/ChatView.js', 'const chat=1', {
        facadeModuleId: 'D:/project/web/src/views/ChatView.vue'
      })
    }
    expect(inspectBundleBudgets(bundle)).toEqual([])
  })

  it('超过预算或缺失目标块时返回明确错误', () => {
    const budgets = [{
      name: '入口',
      matches: output => output.isEntry,
      rawBytes: 4,
      gzipBytes: 100
    }]
    const violations = inspectBundleBudgets({ main: chunk('index.js', '12345', { isEntry: true }) }, budgets)
    expect(violations[0]).toContain('超出预算')
    expect(inspectBundleBudgets({}, budgets)[0]).toContain('没有匹配')
  })
})
