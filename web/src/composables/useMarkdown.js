import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { enhanceCodeBlocks, ensureHighlightDependency } from '@/composables/markdown/codeBlockEnhancer'
import { escapeHtml } from '@/composables/markdown/domActions'
import { enhanceGeneratedMedia } from '@/composables/markdown/mediaEnhancer'
import { ensureMathDependency, extractMathBlocks } from '@/composables/markdown/mathRenderer'
import { renderDepsVersion } from '@/composables/markdown/renderDependencyState'
import { enhanceMarkdownTables } from '@/composables/markdown/tableExporter'
import {
  detectDiagramType,
  getCachedMermaidSvg,
  getDiagramIcon,
  getGlobalTheme,
  handleMermaidToolbarClick,
  reattachMermaidInteractions,
  renderMermaidBlocks,
  setGlobalTheme
} from '@/composables/markdown/mermaidRenderer'

export { handleMermaidToolbarClick, renderMermaidBlocks, setGlobalTheme }
// 样式文件体积很小，保留静态 import 保证主题样式在首屏就绪；
// 体积较大的 hljs / katex JS 改为动态懒加载（首次遇到代码块/公式时才加载），大幅减小首屏 JS
import 'highlight.js/styles/atom-one-dark.css'
import 'katex/dist/katex.min.css'

// ===== 懒加载依赖就绪信号（响应式） =====
// hljs/katex 懒加载完成后递增，触发已渲染消息重新渲染补位。
// 配置 marked
marked.use({
  gfm: true,
  breaks: true,
  renderer: {
    code(code, lang) {
      const escaped = escapeHtml(code)
      const cls = lang ? ` class="language-${lang}"` : ''
      return `<pre><code${cls}>${escaped}</code></pre>`
    }
  }
})

// 链接统一新标签页打开：marked 渲染的 <a> 默认在当前页跳转，会顶掉聊天页面。
// 在 DOMPurify 清洗后阶段统一补 target/rel，同时覆盖 markdown 链接与 AI 输出的裸 HTML 链接；
// 页内锚点（#开头）不处理

DOMPurify.addHook('afterSanitizeAttributes', (node) => {
  if (node.tagName === 'A') {
    const href = node.getAttribute('href') || ''
    if (href && !href.startsWith('#')) {
      node.setAttribute('target', '_blank')
      node.setAttribute('rel', 'noopener noreferrer')
    }
  }
})

// ===== renderMarkdown 结果缓存 =====
// 流式输出时每个 SSE tick 触发组件重渲染，v-html 会对全部历史消息重复执行
// marked.parse + DOMPurify.sanitize（长会话 CPU 开销巨大且结果完全相同）。
// 以 (依赖版本 + 明暗主题 + 文本) 为键缓存渲染结果，历史消息重渲染直接命中。
// 依赖版本入键：katex/hljs 懒加载完成后版本号递增，缓存键自然失效，公式得以补齐渲染；
// 主题入键：mermaid 图表缓存依赖明暗映射，主题切换后重新渲染
const markdownCache = new Map()
const MARKDOWN_CACHE_MAX = 200

// 同步渲染 Markdown：KaTeX/hljs 懒加载就绪前调用时，公式回退原文、代码块稍后由异步补渲染。
// 函数体读取 renderDepsVersion 建立响应式依赖：懒加载完成后触发已渲染消息重新渲染补位；
// 同时按需触发懒加载并预热（首屏不再为不含公式/代码的消息付出加载成本）；
// 渲染结果按 (依赖版本+主题+文本) 缓存，避免流式输出时历史消息重复全量渲染。
export function renderMarkdown(text) {
  // 依赖就绪信号：懒加载完成后触发 Vue 重渲染（此处读取即建立依赖，非死代码）
  const depsVer = renderDepsVersion.value
  if (!text) return ''
  // 预热懒加载：仅在内容可能用到时触发，避免为纯文本消息加载大体积依赖
  ensureMathDependency(text)
  ensureHighlightDependency(text)
  // 命中缓存直接返回（流式输出时历史消息不再重复解析）
  const cacheKey = depsVer + '|' + getGlobalTheme() + '|' + text
  const cached = markdownCache.get(cacheKey)
  if (cached !== undefined) return cached

  // 处理流式输出中的mermaid块（未闭合的```mermaid/mer/mmd，兼容\r\n及尾部空格）
  const streamingMermaidBlocks = []
  text = text.replace(/(```(?:mermaid|mer|mmd)[ \t]*\r?\n[\s\S]*?)(```|$)/g, (match, code, end) => {
    if (end === '```') return match
    streamingMermaidBlocks.push(code.replace(/^```(?:mermaid|mer|mmd)[ \t]*\r?\n/, ''))
    return '%%STREAMING_MERMAID_' + (streamingMermaidBlocks.length - 1) + '%%'
  })

  // 处理已闭合的mermaid代码块（兼容 ```mermaid / ```mer / ```mmd，支持\r\n）
  const mermaidBlocks = []
  text = text.replace(/```(?:mermaid|mer|mmd)[ \t]*\r?\n([\s\S]*?)```/g, (match, code) => {
    const idx = mermaidBlocks.length
    mermaidBlocks.push(code.replace(/\r\n/g, '\n').replace(/\r/g, '\n').trim())
    return '%%MERMAID_' + idx + '%%'
  })

  // 抽取数学公式/化学式为占位符（在 mermaid 抽取之后、marked 解析之前，避免被 marked 转义）
  const mathBlocks = []
  text = extractMathBlocks(text, mathBlocks)

  let html = marked.parse(text)

  // 还原mermaid块（带工具栏卡片）
  // 命中已渲染缓存的图表：用占位符承载已渲染 SVG，DOMPurify 后回填（绕过清洗，SVG 为可信自产输出），
  // 避免流式全量重建时已完成图表被销毁重建而反复回到“渲染中”闪烁
  const renderedMermaidSvgs = []
  mermaidBlocks.forEach((code, idx) => {
    const escaped = escapeHtml(code)
    const typeKey = detectDiagramType(code)
    const typeLabel = typeKey.replace(/diagram$/i, '').toUpperCase()
    const cachedSvg = getCachedMermaidSvg(code)
    const viewPre = cachedSvg != null
      ? `<pre class="mermaid mermaid-rendered" data-processed="true">%%MERMAIDSVG${renderedMermaidSvgs.push(cachedSvg) - 1}%%</pre>`
      : `<pre class="mermaid">${escaped}</pre>`
    const mermaidHtml = `<div class="mermaid-container" data-mermaid-raw="${escaped.replace(/"/g, '&quot;')}">` +
      `<div class="mermaid-toolbar">` +
        `<div class="mermaid-toolbar-left">` +
          `<span class="mermaid-type-tag">${getDiagramIcon(typeKey)} ${typeLabel}</span>` +
          `<div class="mermaid-tabs">` +
            `<button class="mermaid-tab active" data-act="showView" data-pane="view">视图</button>` +
            `<button class="mermaid-tab" data-act="showCode" data-pane="code">代码</button>` +
          `</div>` +
        `</div>` +
        `<div class="mermaid-toolbar-right">` +
          `<button class="mermaid-action" data-act="copy" title="复制代码"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg></button>` +
          `<button class="mermaid-action" data-act="download" title="下载"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>` +
          `<button class="mermaid-action" data-act="fullscreen" title="全屏查看"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg></button>` +
          `<button class="mermaid-action" data-act="toggleTheme" title="切换主题"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="5"/><path d="M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42"/></svg></button>` +
        `</div>` +
      `</div>` +
      `<div class="mermaid-scroll-wrapper"><div class="mermaid-view">${viewPre}</div></div>` +
      `<div class="mermaid-code" style="display:none"><pre><code class="language-mermaid">${escaped}</code></pre></div>` +
    `</div>`
    html = html.replace('%%MERMAID_' + idx + '%%', mermaidHtml)
  })

  // 还原流式mermaid块
  streamingMermaidBlocks.forEach((code, idx) => {
    const escaped = escapeHtml(code)
    const typeKey = detectDiagramType(code)
    const typeLabel = typeKey.replace(/diagram$/i, '').toUpperCase()
    const streamingHtml = `<div class="mermaid-container streaming" data-mermaid-raw="${escaped.replace(/"/g, '&quot;')}">` +
      `<div class="mermaid-toolbar"><div class="mermaid-toolbar-left">` +
        `<span class="mermaid-type-tag">${getDiagramIcon(typeKey)} ${typeLabel}</span>` +
        `<span class="mermaid-streaming-tip"><span class="streaming-dot"></span><span>流式生成中</span></span>` +
      `</div></div>` +
      `<div class="mermaid-scroll-wrapper"><div class="mermaid-view"><pre class="mermaid">${escaped}</pre></div></div>` +
    `</div>`
    html = html.replace('%%STREAMING_MERMAID_' + idx + '%%', streamingHtml)
  })

  // XSS 防护：marked 默认原样输出 Markdown 中的裸 HTML，AI 回复内容经 v-html 渲染，
  // 若含 <script>/onerror 等会造成 XSS（分享页为公开场景，风险尤甚）。用 DOMPurify
  // 清洗，剥离脚本与事件处理器，同时保留 mermaid 工具栏所需的 data-* 属性与内联 SVG。
  let clean = DOMPurify.sanitize(html, { ADD_ATTR: ['target'], ADD_TAGS: ['use'] })

  // 回填 KaTeX 公式 HTML（清洗后插入：KaTeX 以 trust:false 运行，输出为纯数学标记，安全）
  if (mathBlocks.length) {
    mathBlocks.forEach((h, idx) => {
      clean = clean.replaceAll('%%KMATH' + idx + '%%', h)
    })
  }
  // 回填已渲染 mermaid SVG（同为清洗后插入：SVG 为本地 mermaid 渲染的可信输出）
  if (renderedMermaidSvgs.length) {
    renderedMermaidSvgs.forEach((svg, idx) => {
      clean = clean.replaceAll('%%MERMAIDSVG' + idx + '%%', svg)
    })
  }
  // 写入结果缓存：含未闭合流式块的消息内容仍在增长、每次键都不同，跳过缓存避免内存膨胀；
  // 其余消息缓存后，流式输出时历史消息重渲染直接命中
  if (streamingMermaidBlocks.length === 0) {
    if (!markdownCache.has(cacheKey) && markdownCache.size >= MARKDOWN_CACHE_MAX) {
      const oldest = markdownCache.keys().next().value
      if (oldest !== undefined) markdownCache.delete(oldest)
    }
    markdownCache.set(cacheKey, clean)
  }
  return clean
}

/** 对 Markdown 渲染结果执行表格、代码、媒体和 Mermaid 的 DOM 增强。 */
export function processSpecialContent(container) {
  if (!container) return
  enhanceMarkdownTables(container)
  enhanceCodeBlocks(container)
  enhanceGeneratedMedia(container)
  reattachMermaidInteractions(container)
}

/** 返回 Markdown 渲染的兼容组合式入口。 */
export function useMarkdown() {
  return { renderMarkdown, processSpecialContent, escapeHtml, setGlobalTheme, renderDepsVersion }
}

export { escapeHtml, renderDepsVersion }
