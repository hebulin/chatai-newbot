import { attachInlinePanZoom } from './mermaidPanZoom'
import { cleanSvgForExport, svgToPng, openMermaidFullscreen } from './mermaidExport'
import { copyTextWithFallback, downloadFile, escapeHtml, showToast } from '@/composables/markdown/domActions'
import { loadMermaidRuntime } from '@/composables/markdown/lazyDependencies'
import { onElementDetached } from '@/composables/markdown/domLifecycle'

// 全局明暗主题标记（渲染入口在渲染前调用 setGlobalTheme 同步），
// 供 getCurrentMermaidTheme() 无参调用时读取当前明暗，保持行为与传参一致
let currentGlobalTheme = 'dark'
/** 同步全局明暗主题供 Markdown 和 Mermaid 缓存使用。 */
export function setGlobalTheme(t) {
  if (t === 'dark' || t === 'light') currentGlobalTheme = t
}

/** 返回 Markdown 缓存键使用的当前全局主题。 */
export function getGlobalTheme() {
  return currentGlobalTheme
}

// ===== Mermaid 懒加载与渲染 =====
let mermaidModule = null
let mermaidInitialized = false
let lastInitTheme = null
let renderQueue = []
let isRendering = false
let uidCounter = 0

// ===== 已渲染 mermaid SVG 缓存 =====
// 流式输出时整条消息每次 tick 都会经 v-html 全量重建，已完成的 mermaid 图表 DOM
// 被销毁重建、反复回到“渲染中”占位而闪烁。以 主题+代码 为键缓存裁剪后的最终 SVG，
// 后续重建时命中缓存直接回填已渲染结果，跳过重复渲染，消除闪烁。
const mermaidSvgCache = new Map()
const MERMAID_SVG_CACHE_MAX = 200
/** 生成主题与源码隔离的 SVG 缓存键。 */
function mermaidCacheKey(theme, code) {
  return (theme || 'default') + '\u0000' + code
}
/** 读取当前默认主题下的已渲染 SVG。 */
export function getCachedMermaidSvg(code) {
  return mermaidSvgCache.get(mermaidCacheKey(getCurrentMermaidTheme(), code))
}
/** 写入 SVG 缓存并按容量淘汰最早记录。 */
function setCachedMermaidSvg(theme, code, svg) {
  const key = mermaidCacheKey(theme, code)
  // 简单 FIFO 淘汰，避免长会话缓存无限增长
  if (!mermaidSvgCache.has(key) && mermaidSvgCache.size >= MERMAID_SVG_CACHE_MAX) {
    const oldest = mermaidSvgCache.keys().next().value
    if (oldest !== undefined) mermaidSvgCache.delete(oldest)
  }
  mermaidSvgCache.set(key, svg)
}

// Mermaid 主题列表（单图独立切换，仅在本次页面会话内有效）
const MERMAID_THEMES = [
  { key: 'default', name: '默认' },
  { key: 'neutral', name: '中性' },
  { key: 'forest', name: '森林' },
  { key: 'dark', name: '深色' },
  { key: 'base', name: '极简' }
]

/** 生成单次图表渲染的唯一 DOM 标识。 */
function nextUid() {
  return 'mermaid-' + Date.now().toString(36) + '-' + (++uidCounter)
}

/** 按需取得 Mermaid 单例。 */
async function loadMermaid() {
  if (mermaidModule) return mermaidModule
  mermaidModule = await loadMermaidRuntime()
  return mermaidModule
}

// 按 mermaid 主题键初始化；主题变化时重新 initialize（用于单图主题切换重渲染）
function ensureMermaidInit(themeKey) {
  if (!mermaidModule) return
  if (mermaidInitialized && themeKey === lastInitTheme) return
  try {
    mermaidModule.initialize({
      startOnLoad: false,
      theme: themeKey || 'default',
      // strict：禁用图表内脚本/事件处理器并对标签 HTML 转义，防止 AI 生成的
      // mermaid 代码在分享页等公开场景触发 XSS；仍为内联 SVG，不影响自定义工具栏
      securityLevel: 'strict'
    })
    mermaidInitialized = true
    lastInitTheme = themeKey
  } catch (e) {
    console.warn('[useMarkdown] mermaid.initialize failed:', e)
  }
}

// 获取 mermaid 默认主题：按全局明暗主题映射。不读 localStorage--
// 切换主题仅影响当前图表本次会话，刷新后所有图回到跟随全局明暗的默认主题。
// globalTheme 缺省时读取渲染入口同步的当前明暗（setGlobalTheme），行为与传参一致
function getCurrentMermaidTheme(globalTheme) {
  const t = globalTheme || currentGlobalTheme
  return t === 'dark' ? 'dark' : 'default'
}

/**
 * AI 生成 mermaid 代码的容错预处理：换行归一化 + 流程图智能分拆 +
 * 标签引号化 + subgraph 样式修复 + 时序图修复，提升不规范语法的渲染成功率
 */
function normalizeMermaidCode(code) {
  if (!code) return code
  code = code.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const lines = code.split('\n')
  const normalized = []
  const diagramType = detectDiagramType(code)
  const isFlowchartType = (diagramType === 'flowchart' || diagramType === 'graph')
  const isSequenceType = (diagramType === 'sequencediagram')
  let seqParticipants = []
  if (isSequenceType) {
    lines.forEach(line => {
      const m = line.trim().match(/^participant\s+(\w+)\s+as\s+/)
      if (m) seqParticipants.push(m[1])
    })
  }
  lines.forEach(line => {
    const trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('%')) { normalized.push(line); return }
    if (isFlowchartType) {
      splitMermaidLine(trimmed).forEach(part => {
        let p = part.trim()
        if (!p) return
        if (p.startsWith('subgraph ')) p = fixSubgraphLine(p)
        p = quoteMermaidLabels(p)
        normalized.push(p)
      })
    } else if (isSequenceType) {
      let p = trimmed
      if (p.startsWith('subgraph ')) p = fixSubgraphLine(p)
      p = fixSequenceDiagramLine(p, seqParticipants)
      normalized.push(p)
    } else {
      let p2 = trimmed
      if (p2.startsWith('subgraph ')) p2 = fixSubgraphLine(p2)
      normalized.push(p2)
    }
  })
  return normalized.join('\n')
}

// 时序图行修复：非标准语法行转为 Note over 形式，避免渲染失败
function fixSequenceDiagramLine(line, participants) {
  const trimmed = line.trim()
  if (/^(sequenceDiagram|graph|flowchart|classDiagram|stateDiagram|stateDiagram-v2|gantt|pie|gitGraph|erDiagram|journey|mindmap|timeline|quadrantChart)\b/i.test(trimmed)) return line
  if (/^(participant|actor|create|destroy|note|Note|loop|end|alt|else|opt|rect|autonumber|activate|deactivate)\b/.test(trimmed)) return line
  if (/^\w+\s*(-?>+|--?>>+|->|\.)+\s*\w*\s*:/.test(trimmed)) return line
  const target = participants.length > 0 ? participants[0] : 'arr'
  const text = trimmed.replace(/"/g, '\\"')
  return 'Note over ' + target + ': ' + text
}

// subgraph 行修复：去除末尾 :::style 样式标记（mermaid 不支持在 subgraph 上用 class 样式）
function fixSubgraphLine(line) {
  return line.replace(/^(subgraph\s+)(.+?)(:::\w+)+(\s*)$/, (m, prefix, title, style, tail) => {
    return prefix + title + tail
  })
}

// 流程图行智能分拆：按 2+ 空格或 tab 缩进拆分同行多语句（mermaid 要求每条语句独立成行）
function splitMermaidLine(line) {
  const parts = []
  let current = ''
  let bracketDepth = 0
  let parenDepth = 0
  let i = 0
  while (i < line.length) {
    const ch = line[i]
    if (ch === '[') bracketDepth++
    else if (ch === ']') bracketDepth = Math.max(0, bracketDepth - 1)
    else if (ch === '(') parenDepth++
    else if (ch === ')') parenDepth = Math.max(0, parenDepth - 1)
    if (bracketDepth <= 0 && parenDepth <= 0) {
      const match = line.substring(i).match(/^(\s{2,}|\t+)/)
      if (match) {
        const sepLen = match[1].length
        const before = current.trim()
        const after = line.substring(i + sepLen).trim()
        if (before && after) {
          parts.push(current)
          current = ''
          i += sepLen
          continue
        }
      }
    }
    current += ch
    i++
  }
  if (current.trim()) parts.push(current)
  return parts
}

// 节点标签引号化：标签含特殊字符时用双引号包裹，避免解析歧义。
// label 内不含 [ ] （用 [^\[\]] 限定），含嵌套方括号的标签（通常已引号化，
// 如 A["x<b>[1,2]</b>"]）保持原样，避免正则截断到内部 ] 后错误重新引号化破坏语法。
function quoteMermaidLabels(line) {
  return line.replace(/(\w+)\[([^\[\]]*)\]/g, (match, nodeId, label) => {
    if (label.startsWith('"') && label.endsWith('"')) return match
    if (/[(){}\[\]#&]/.test(label)) {
      const escapedLabel = label.replace(/"/g, '\\"')
      return nodeId + '["' + escapedLabel + '"]'
    }
    return match
  })
}

/** 将图表任务加入串行渲染队列。 */
function enqueueRender(el, text, rawCode) {
  renderQueue.push({ el, text, rawCode })
  drainQueue()
}

// parse 验证后入队渲染：先验证 normalize 后的代码，parse 失败则回退 raw 原文。
// 避免 normalize（如 quoteMermaidLabels）误伤已引号化的复杂标签导致渲染失败；
// 初次渲染与主题切换重渲染共用此逻辑，保证两者行为一致。
// 两者均 parse 失败时标记 mermaid-parse-failed（退出视图模式 loading 占位，回退显示原始代码）
function parseAndEnqueueRender(el, normalized, raw) {
  const renderWith = (code) => { if (el.isConnected) enqueueRender(el, code, raw) }
  const markFailed = () => { if (el.isConnected) el.classList.add('mermaid-parse-failed') }
  if (!mermaidModule) { renderWith(normalized); return }
  try {
    const r = mermaidModule.parse(normalized)
    if (r && typeof r.then === 'function') {
      r.then(() => renderWith(normalized)).catch(() => tryRenderRaw(raw, renderWith, markFailed))
    } else {
      renderWith(normalized)
    }
  } catch (e) {
    tryRenderRaw(raw, renderWith, markFailed)
  }
}
// 回退用 raw 原文 parse 验证后渲染，仍失败则通知 onFail
function tryRenderRaw(raw, renderWith, onFail) {
  try {
    const r2 = mermaidModule.parse(raw)
    if (r2 && typeof r2.then === 'function') r2.then(() => renderWith(raw)).catch(() => { if (onFail) onFail() })
    else renderWith(raw)
  } catch (e2) { if (onFail) onFail() }
}

/** 逐个处理渲染任务，避免 Mermaid 全局状态并发冲突。 */
function drainQueue() {
  if (isRendering) return
  const item = renderQueue.shift()
  if (!item) return
  const { el, text, rawCode } = item
  if (!el.isConnected) { drainQueue(); return }
  isRendering = true

  // 按图表独立主题或全局 mermaid 主题初始化
  const container = el.closest('.mermaid-container')
  const themeToUse = (container && container.dataset.mermaidTheme) || getCurrentMermaidTheme()
  ensureMermaidInit(themeToUse)

  const id = nextUid()
  mermaidModule.render(id, text).then(result => {
    if (el.isConnected) {
      el.innerHTML = result.svg
      // 保留 mermaid class（CSS 尺寸规则写在 pre.mermaid svg 上），仅追加
      // mermaid-rendered 作为已渲染标记；否则移除 mermaid 会使 CSS 选择器失配，
      // 在 cropSvgViewBox 移除 width/height 后 SVG 失去尺寸来源而不显示
      el.classList.add('mermaid-rendered')
      const renderedSvg = el.querySelector('svg')
      if (renderedSvg) cropSvgViewBox(renderedSvg)
      attachInlinePanZoom(el)
      // 缓存裁剪后的 SVG：流式全量重建时命中缓存直接回填，避免已完成图表反复闪烁；
      // 未闭合的流式块代码仍会变化，不缓存
      if (rawCode && !el.closest('.mermaid-container.streaming')) {
        setCachedMermaidSvg(themeToUse, rawCode, el.innerHTML)
      }
    }
  }).catch(err => {
    console.warn('[useMarkdown] mermaid render failed:', err?.message || err)
    if (!el.isConnected) return
    // 流式输出中的 mermaid 块渲染失败时保留原始代码文本，不显示错误，等待代码补全后再渲染
    const streamingContainer = el.closest('.mermaid-container.streaming')
    if (streamingContainer) return
    el.classList.remove('mermaid')
    el.classList.add('mermaid-error')
    el.innerHTML =
      '<div class="mermaid-error-tip">⚠ Mermaid 图表渲染失败</div>' +
      '<pre style="margin:0;white-space:pre-wrap;"><code>' + escapeHtml(text) + '</code></pre>'
  }).then(() => {
    // 清理 mermaid 渲染失败遗留的 body 临时元素（选择器与旧版对齐）
    document.querySelectorAll('body > svg').forEach(svg => {
      try {
        const txt = svg.textContent || ''
        if (txt.includes('Syntax error') || txt.includes('Parse error') ||
            txt.includes('mermaid') || txt.includes('version')) {
          svg.remove()
        }
      } catch (e) { /* ignore */ }
    })
    document.querySelectorAll('body > div[id^="dmermaid-"], body > div[id^="mermaid-"]').forEach(d => {
      if (!d.closest('.chat-container')) d.remove()
    })
    document.querySelectorAll('body > .mermaid, body > [class*="mermaid"]').forEach(el2 => {
      if (!el2.closest('.chat-container')) el2.remove()
    })
    isRendering = false
    drainQueue()
  })
}

/**
 * 裁剪 viewBox 空白边距：mermaid 输出的 viewBox 可能远大于实际内容区域
 * （如画布 12x8 但内容只占 6x2），导致适配缩放后图表显得很小。
 * 用 getBBox 计算实际内容边界并重写 viewBox，让图表填满显示区域。
 *
 * 注意：必须同时移除 mermaid 写入的 width/height 属性。SVG 在同时拥有
 * width 与 height 属性时，固有宽高比由这两个属性决定（而非 viewBox），
 * 若仅裁剪 viewBox 而保留原始 width/height，则显示框仍按原始比例计算
 * （如 12:8），与裁剪后 viewBox 比例（如 6:2）不一致，在 meet 模式下
 * 内容会被缩小并留白。移除 width/height 后固有宽高比由 viewBox 决定，
 * 显示框即可与实际内容区域匹配。
 */
function cropSvgViewBox(svg) {
  try {
    // 清除 mermaid 写入的内联 style 与固定 width/height，
    // 尺寸完全交由 CSS + viewBox 比例控制
    svg.removeAttribute('style')
    svg.removeAttribute('width')
    svg.removeAttribute('height')
    const b = svg.getBBox()
    if (!b || (b.width <= 0 && b.height <= 0)) return
    const pad = 8
    const x = Math.floor(b.x - pad)
    const y = Math.floor(b.y - pad)
    const w = Math.ceil(b.width + pad * 2)
    const h = Math.ceil(b.height + pad * 2)
    svg.setAttribute('viewBox', x + ' ' + y + ' ' + w + ' ' + h)
  } catch (e) { /* 元素不可见等场景下 getBBox 可能失败，保留原 viewBox */ }
}

/** 为 v-html 重建后命中 SVG 缓存的 Mermaid 节点重新绑定交互。 */
export function reattachMermaidInteractions(container) {
  container.querySelectorAll('.mermaid-view pre.mermaid.mermaid-rendered').forEach(pre => {
    attachInlinePanZoom(pre)
  })
}

/**
 * 渲染容器内所有未处理的 mermaid 块
 * @param {HTMLElement} container - 要处理的 DOM 容器
 * @param {string} theme - 当前主题 'dark' | 'light'
 */
export async function renderMermaidBlocks(container, theme = 'dark') {
  if (!container) return
  const els = container.querySelectorAll('.mermaid-view pre.mermaid:not([data-processed])')
  if (!els.length) return

  try {
    await loadMermaid()
  } catch (e) {
    // mermaid 懒加载失败（如网络异常）：标记失败退出 loading 占位，回退显示原始代码
    console.warn('[useMarkdown] mermaid load failed:', e?.message || e)
    els.forEach(el => el.classList.add('mermaid-parse-failed'))
    return
  }
  ensureMermaidInit(getCurrentMermaidTheme(theme))

  els.forEach(el => {
    el.setAttribute('data-processed', 'true')
    const rawText = el.textContent
    // parse 验证 normalize 后代码，失败回退原文（共用逻辑，避免 normalize 误伤）
    parseAndEnqueueRender(el, normalizeMermaidCode(rawText), rawText)
  })
}

// ===== Mermaid 图表类型检测与图标 =====
export function detectDiagramType(code) {
  if (!code) return 'graph'
  const first = code.trim().split('\n')[0].trim()
  const m = first.match(/^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|stateDiagram-v2|gantt|pie|gitGraph|erDiagram|journey|mindmap|timeline|quadrantChart|requirementDiagram|C4Context|C4Container|C4Component|C4Dynamic|sankey|block|packet|architecture)\b/i)
  if (m) return m[1].toLowerCase()
  return 'graph'
}

/** 生成图表类型标签所需的内联图标。 */
export function getDiagramIcon(typeKey) {
  let s = '<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">'
  if (typeKey === 'sequencediagram') {
    s += '<line x1="6" y1="3" x2="6" y2="21"/><line x1="18" y1="3" x2="18" y2="21"/><polyline points="6 8 18 8 12 14 6 8 18 8"/>'
  } else if (typeKey === 'gantt') {
    s += '<line x1="3" y1="12" x2="21" y2="12"/><rect x="4" y="9" width="6" height="3"/><rect x="12" y="9" width="8" height="3"/>'
  } else if (typeKey === 'pie') {
    s += '<path d="M12 2v10l8.66 5A10 10 0 1 1 12 2z"/>'
  } else if (typeKey === 'classdiagram') {
    s += '<rect x="3" y="4" width="18" height="16" rx="1"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="3" y1="15" x2="21" y2="15"/>'
  } else if (typeKey.startsWith('state')) {
    s += '<circle cx="6" cy="6" r="2"/><circle cx="6" cy="18" r="2"/><circle cx="18" cy="12" r="2"/><line x1="8" y1="6" x2="16" y2="11"/><line x1="8" y1="18" x2="16" y2="13"/>'
  } else if (typeKey === 'erdiagram') {
    s += '<rect x="3" y="4" width="8" height="6"/><rect x="13" y="4" width="8" height="6"/><rect x="8" y="14" width="8" height="6"/><line x1="11" y1="7" x2="13" y2="7"/><line x1="12" y1="10" x2="12" y2="14"/>'
  } else {
    s += '<rect x="3" y="3" width="7" height="5" rx="1"/><rect x="14" y="3" width="7" height="5" rx="1"/><rect x="8" y="14" width="8" height="5" rx="1"/><line x1="10" y1="8" x2="12" y2="14"/><line x1="14" y1="14" x2="17" y2="8"/>'
  }
  return s + '</svg>'
}

// ===== Mermaid 工具栏事件处理 =====
export function handleMermaidToolbarClick(e) {
  const btn = e.target.closest('.mermaid-action[data-act], .mermaid-tab[data-act]')
  if (!btn) return
  const act = btn.getAttribute('data-act')
  const container = btn.closest('.mermaid-container')
  if (!container) return

  switch (act) {
    case 'showView':
    case 'showCode': {
      const pane = act === 'showView' ? 'view' : 'code'
      container.querySelectorAll('.mermaid-tab').forEach(t => {
        t.classList.toggle('active', t.getAttribute('data-pane') === pane)
      })
      const view = container.querySelector('.mermaid-scroll-wrapper')
      const code = container.querySelector('.mermaid-code')
      if (view) view.style.display = pane === 'view' ? '' : 'none'
      if (code) code.style.display = pane === 'code' ? 'block' : 'none'
      // 切换工具栏按钮可见性：视图模式显 下载/主题/全屏，代码模式显 复制代码
      container.classList.toggle('mermaid-mode-code', pane === 'code')
      break
    }
    case 'copy': {
      const raw = container.getAttribute('data-mermaid-raw') || ''
      const originalHtml = btn.innerHTML
      const onSuccess = () => {
        btn.classList.add('success')
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="2.5"><polyline points="20 6 9 17 4 12"/></svg>'
        setTimeout(() => {
          btn.classList.remove('success')
          btn.innerHTML = originalHtml
        }, 2000)
      }
      copyTextWithFallback(raw, onSuccess, () => showToast('复制失败'))
      break
    }
    case 'download': {
      // 弹出下载选项菜单（SVG / PNG），由菜单项触发实际下载
      showMermaidDownloadMenu(btn, container)
      break
    }
    case 'fullscreen': {
      const svg = container.querySelector('.mermaid-view svg')
      if (!svg) return
      openMermaidFullscreen(svg)
      break
    }
    case 'toggleTheme': {
      showMermaidThemeMenu(btn, container)
      break
    }
  }
}

// 复制文本：优先 clipboard API，失败回退 execCommand（兼容 HTTP 环境与旧浏览器）
// 显示 mermaid 主题切换菜单（每图独立切换，点击外部关闭）
function showMermaidThemeMenu(btn, container) {
  if (!container) return
  const existing = container.querySelector('.mermaid-theme-menu')
  if (existing) { closeMermaidMenu(existing); return }
  const cur = container.dataset.mermaidTheme || getCurrentMermaidTheme()
  const menu = document.createElement('div')
  menu.className = 'mermaid-theme-menu active'
  let html = ''
  MERMAID_THEMES.forEach(t => {
    const checked = t.key === cur
    html += '<div class="mermaid-theme-menu-item' + (checked ? ' active' : '') + '" data-theme="' + t.key + '">' +
      '<span class="check">' + (checked ? '✓' : '') + '</span>' +
      '<span>' + t.name + '</span>' +
    '</div>'
  })
  menu.innerHTML = html
  const toolbar = container.querySelector('.mermaid-toolbar')
  if (toolbar) { toolbar.style.position = 'relative'; toolbar.appendChild(menu) }
  else { container.appendChild(menu) }
  const closeMenu = bindMermaidMenuLifecycle(menu, btn)
  menu.querySelectorAll('.mermaid-theme-menu-item').forEach(item => {
    item.addEventListener('click', () => {
      applyMermaidTheme(container, item.getAttribute('data-theme'))
      closeMenu()
    })
  })
}

// 显示 mermaid 下载选项菜单（SVG/PNG），点击选项触发对应下载，点击外部关闭
function showMermaidDownloadMenu(btn, container) {
  if (!container) return
  const existing = container.querySelector('.mermaid-download-menu')
  if (existing) { closeMermaidMenu(existing); return }
  const menu = document.createElement('div')
  menu.className = 'mermaid-download-menu active'
  menu.innerHTML =
    '<div class="mermaid-download-menu-item" data-fmt="svg"><span>下载 SVG</span></div>' +
    '<div class="mermaid-download-menu-item" data-fmt="png"><span>下载 PNG</span></div>'
  const toolbar = container.querySelector('.mermaid-toolbar')
  if (toolbar) { toolbar.style.position = 'relative'; toolbar.appendChild(menu) }
  else { container.appendChild(menu) }
  const closeMenu = bindMermaidMenuLifecycle(menu, btn)
  menu.querySelectorAll('.mermaid-download-menu-item').forEach(item => {
    item.addEventListener('click', () => {
      const fmt = item.getAttribute('data-fmt')
      const svg = container.querySelector('.mermaid-view svg')
      if (svg) {
        if (fmt === 'svg') {
          const svgString = new XMLSerializer().serializeToString(cleanSvgForExport(svg))
          downloadFile(svgString, 'mermaid-' + Date.now() + '.svg', 'image/svg+xml')
        } else {
          svgToPng(cleanSvgForExport(svg), 'mermaid-' + Date.now() + '.png')
        }
      }
      closeMenu()
    })
  })
}

// 为 Mermaid 浮动菜单绑定一次性外部点击清理，并返回统一关闭函数。
function bindMermaidMenuLifecycle(menu, btn) {
  let closed = false
  let documentBound = false
  const cancelDetachWatch = onElementDetached(menu, close)
  const onDocumentClick = (event) => {
    if (menu.contains(event.target) || btn.contains(event.target)) return
    close()
  }
  // 所有关闭路径统一释放观察器、定时器和外部点击监听。
  function close() {
    if (closed) return
    closed = true
    cancelDetachWatch()
    clearTimeout(bindTimer)
    menu.remove()
    if (documentBound) document.removeEventListener('click', onDocumentClick)
  }
  menu.addEventListener('mermaid-menu-close', close, { once: true })
  const bindTimer = setTimeout(() => {
    if (closed || !menu.isConnected) { close(); return }
    documentBound = true
    document.addEventListener('click', onDocumentClick)
  }, 0)
  return close
}

// 触发菜单自身的生命周期清理，兼容工具栏二次点击关闭路径。
function closeMermaidMenu(menu) {
  menu.dispatchEvent(new Event('mermaid-menu-close'))
  if (menu.isConnected) menu.remove()
}

// 应用 mermaid 主题到单个图表并重渲染（仅影响当前图表本次会话，不持久化、不影响其他图表）
function applyMermaidTheme(container, theme) {
  if (!container) return
  container.dataset.mermaidTheme = theme
  const view = container.querySelector('.mermaid-view')
  const pre = view ? view.querySelector('pre') : null
  if (!pre) return
  const raw = container.getAttribute('data-mermaid-raw')
  if (!raw) return
  pre.classList.add('mermaid')
  pre.classList.remove('mermaid-rendered', 'mermaid-error', 'mermaid-parse-failed')
  pre.removeAttribute('data-processed')
  pre.innerHTML = escapeHtml(raw)
  ensureMermaidInit(theme)
  // 与初次渲染一致：parse 验证 normalize 后代码，失败回退原文，避免 normalize 误伤导致渲染失败
  parseAndEnqueueRender(pre, normalizeMermaidCode(raw), raw)
}
