/**
 * 搜索高亮 composable：在离屏 HTML 中精确包裹关键词，返回给 v-html 一次性渲染。
 * 职责边界：只处理"已渲染完成的 HTML 字符串"的关键词标记，不触碰 Vue 已挂载的真实 DOM
 *（直接改真实 DOM 会破坏 VNode↔DOM 映射，曾导致会话切换崩溃，详见项目记忆）。
 */
import { renderMarkdown, escapeHtml } from '@/composables/useMarkdown'

/**
 * 在离屏 HTML 中包裹关键词
 * @param html 已渲染的 HTML 字符串
 * @param keyword 搜索关键词（空串返回原 HTML）
 * @param isActive 是否当前命中项（强化高亮样式）
 * @returns 标记后的 HTML 字符串
 */
export function highlightHtml(html, keyword, isActive) {
  if (!keyword || !keyword.trim()) return html
  const keywordLower = keyword.trim().toLowerCase()
  const root = document.createElement('div')
  root.innerHTML = html
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  const textNodes = []
  let node = walker.nextNode()
  while (node) {
    const parent = node.parentElement
    // 代码、公式、图表与操作按钮有独立的后处理流程，搜索标记不介入其内部 DOM
    const skip = parent?.closest('script, style, svg, button, input, textarea, pre, code, .code-header, .mermaid-container, .katex')
    if (!skip && node.textContent?.toLowerCase().includes(keywordLower)) textNodes.push(node)
    node = walker.nextNode()
  }

  textNodes.forEach(textNode => {
    const text = textNode.textContent || ''
    const lower = text.toLowerCase()
    const fragment = document.createDocumentFragment()
    let cursor = 0
    let matchAt = lower.indexOf(keywordLower)
    while (matchAt !== -1) {
      if (matchAt > cursor) fragment.append(document.createTextNode(text.slice(cursor, matchAt)))
      const mark = document.createElement('mark')
      mark.className = `msg-search-match${isActive ? ' msg-search-match-active' : ''}`
      mark.textContent = text.slice(matchAt, matchAt + keyword.trim().length)
      fragment.append(mark)
      cursor = matchAt + keyword.trim().length
      matchAt = lower.indexOf(keywordLower, cursor)
    }
    if (cursor < text.length) fragment.append(document.createTextNode(text.slice(cursor)))
    textNode.replaceWith(fragment)
  })
  return root.innerHTML
}

/**
 * 创建消息内容渲染器：Markdown 渲染 + 按需搜索高亮
 * @param getContext 返回 { keyword, activeIndex, startIndex } 的回调（从组件 props 读取）
 * @returns renderMd(text, displayIdx) 与 formatUserContent(content, displayIdx)
 */
export function useSearchHighlight(getContext) {
  // 渲染 Markdown；仅历史消息正文传入展示下标并叠加搜索高亮，思考过程与流式消息保持原样
  function renderMd(text, displayIdx = -1) {
    if (displayIdx < 0) return renderMarkdown(text)
    const ctx = getContext()
    return highlightHtml(renderMarkdown(text), ctx.keyword, displayIdx + ctx.startIndex === ctx.activeIndex)
  }

  // 转义用户/错误消息并按需叠加离屏搜索高亮，确保 v-html 输入安全
  function formatUserContent(content, displayIdx = -1) {
    const html = escapeHtml(content).replace(/\n/g, '<br>')
    if (displayIdx < 0) return html
    const ctx = getContext()
    return highlightHtml(html, ctx.keyword, displayIdx + ctx.startIndex === ctx.activeIndex)
  }

  return { renderMd, formatUserContent }
}
