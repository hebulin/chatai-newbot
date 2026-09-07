import { copyTextWithFallback, downloadFile, showToast } from '@/composables/markdown/domActions'
import { markRenderDependencyReady } from '@/composables/markdown/renderDependencyState'

let highlightModule = null
let highlightLoading = null

const CODE_EXTENSIONS = {
  javascript: 'js', js: 'js', jsx: 'jsx', typescript: 'ts', ts: 'ts', tsx: 'tsx',
  python: 'py', py: 'py', java: 'java', c: 'c', cpp: 'cpp', 'c++': 'cpp', csharp: 'cs', 'c#': 'cs',
  go: 'go', rust: 'rs', rs: 'rs', php: 'php', ruby: 'rb', rb: 'rb', swift: 'swift', kotlin: 'kt',
  html: 'html', xml: 'xml', svg: 'svg', css: 'css', scss: 'scss', less: 'less',
  json: 'json', yaml: 'yml', yml: 'yml', toml: 'toml', ini: 'ini',
  sql: 'sql', shell: 'sh', bash: 'sh', sh: 'sh', powershell: 'ps1',
  markdown: 'md', md: 'md', vue: 'vue', dockerfile: 'Dockerfile', makefile: 'Makefile'
}

/** 内容可能含代码时按需启动 highlight.js 加载。 */
export function ensureHighlightDependency(text) {
  if (text.includes('```') || text.includes('`')) loadHighlightModule()
}

/** 为 Markdown 容器中的普通代码块补充高亮、复制、下载和 HTML 预览操作。 */
export function enhanceCodeBlocks(container) {
  let needsHighlightLoad = false
  container.querySelectorAll('pre code').forEach(block => {
    const language = (block.className.match(/language-(\w+)/) || ['', ''])[1]
    if (language === 'mermaid' || language === 'mer' || language === 'mmd') return
    if (!block.dataset.processed) {
      if (highlightModule) highlightBlock(block)
      else {
        block.dataset.pendingHljs = 'true'
        needsHighlightLoad = true
      }
    }
    attachCodeHeader(block, language)
  })
  if (needsHighlightLoad) loadHighlightModule().then(() => flushPendingHighlights(container))
}

/** 按需加载常用语言高亮包。 */
function loadHighlightModule() {
  if (highlightModule) return Promise.resolve(highlightModule)
  if (!highlightLoading) {
    highlightLoading = import('highlight.js/lib/common').then(module => {
      highlightModule = module.default || module
      markRenderDependencyReady()
      return highlightModule
    }).catch(error => {
      console.warn('[codeBlockEnhancer] highlight.js load failed:', error)
      highlightLoading = null
      return null
    })
  }
  return highlightLoading
}

/** 高亮单个代码块并标记处理状态。 */
function highlightBlock(block) {
  block.dataset.processed = 'true'
  try { highlightModule.highlightElement(block) } catch { /* 未识别语言回退纯文本。 */ }
}

/** 补齐异步依赖加载前暂存的代码块。 */
function flushPendingHighlights(container) {
  if (!highlightModule || !container) return
  container.querySelectorAll('pre code[data-pending-hljs]').forEach(block => {
    delete block.dataset.pendingHljs
    if (!block.dataset.processed) highlightBlock(block)
  })
}

/** 为代码块创建操作栏并绑定行为。 */
function attachCodeHeader(block, language) {
  const pre = block.parentElement
  if (!pre || pre.querySelector('.code-header')) return
  const header = document.createElement('div')
  header.className = 'code-header'
  const isHtml = language.toLowerCase() === 'html'
  header.innerHTML = `<span>${language || 'text'}</span><div class="code-header-actions">` +
    (isHtml ? '<button class="code-preview-btn" title="预览 HTML"><svg width="12" height="12" viewBox="0 0 24 24" fill="currentColor"><polygon points="7 4 20 12 7 20 7 4"/></svg></button>' : '') +
    '<button class="code-download-btn" title="下载代码"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>' +
    '<button class="code-copy-btn">复制代码</button></div>'
  pre.insertBefore(header, pre.firstChild)
  header.querySelector('.code-download-btn').addEventListener('click', event => {
    event.stopPropagation()
    downloadFile(block.textContent, `code-${Date.now()}.${codeFileExtension(language)}`, 'text/plain;charset=utf-8')
  })
  if (isHtml) {
    header.querySelector('.code-preview-btn').addEventListener('click', event => {
      event.stopPropagation()
      pre.dispatchEvent(new CustomEvent('html-preview', { detail: { code: block.textContent }, bubbles: true }))
    })
  }
  header.querySelector('.code-copy-btn').addEventListener('click', function () {
    copyTextWithFallback(block.textContent, () => {
      this.textContent = '已复制!'
      setTimeout(() => { this.textContent = '复制代码' }, 2000)
    }, () => showToast('复制失败'))
  })
}

/** 将代码语言映射为下载文件扩展名。 */
function codeFileExtension(language) {
  return CODE_EXTENSIONS[(language || '').toLowerCase()] || 'txt'
}
