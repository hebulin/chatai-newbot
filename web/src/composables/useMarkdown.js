import { marked } from 'marked'
import DOMPurify from 'dompurify'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'

// ===== Mermaid 懒加载与渲染 =====
let mermaidModule = null
let mermaidLoading = null
let mermaidInitialized = false
let lastInitTheme = null
let renderQueue = []
let isRendering = false
let uidCounter = 0

// Mermaid 主题列表（与旧版一致，支持单图独立切换并持久化到 localStorage）
const MERMAID_THEMES = [
  { key: 'default', name: '默认' },
  { key: 'neutral', name: '中性' },
  { key: 'forest', name: '森林' },
  { key: 'dark', name: '深色' },
  { key: 'base', name: '极简' }
]

function nextUid() {
  return 'mermaid-' + Date.now().toString(36) + '-' + (++uidCounter)
}

async function loadMermaid() {
  if (mermaidModule) return mermaidModule
  if (mermaidLoading) return mermaidLoading
  mermaidLoading = import('mermaid').then(m => {
    mermaidModule = m.default || m
    return mermaidModule
  })
  return mermaidLoading
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
function getCurrentMermaidTheme(globalTheme) {
  return globalTheme === 'dark' ? 'dark' : 'default'
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

function enqueueRender(el, text) {
  renderQueue.push({ el, text })
  drainQueue()
}

// parse 验证后入队渲染：先验证 normalize 后的代码，parse 失败则回退 raw 原文。
// 避免 normalize（如 quoteMermaidLabels）误伤已引号化的复杂标签导致渲染失败；
// 初次渲染与主题切换重渲染共用此逻辑，保证两者行为一致。
// 两者均 parse 失败时标记 mermaid-parse-failed（退出视图模式 loading 占位，回退显示原始代码）
function parseAndEnqueueRender(el, normalized, raw) {
  const renderWith = (code) => { if (el.isConnected) enqueueRender(el, code) }
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

function drainQueue() {
  if (isRendering) return
  const item = renderQueue.shift()
  if (!item) return
  const { el, text } = item
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

// ===== 聊天区内联缩放/拖拽 =====
// 采用 viewBox 缩放方案：通过修改 SVG 的 viewBox 属性实现缩放/平移，
// 而非 CSS transform。viewBox 缩放改变 SVG 内部坐标系，所有内容（含
// foreignObject 内的 HTML 标签文字）按新坐标系矢量重渲染，避免 CSS
// transform + will-change 触发 GPU 合成层栅格化导致的放大模糊问题。
function attachInlinePanZoom(preEl) {
  const svg = preEl.querySelector('svg')
  const wrapper = preEl.closest('.mermaid-scroll-wrapper')
  if (!svg || !wrapper || wrapper.dataset.panzoomBound) return
  // 读取裁剪后的初始 viewBox（由 cropSvgViewBox 写入），无则无法缩放
  const vbStr = svg.getAttribute('viewBox')
  if (!vbStr) return
  const m = vbStr.split(/[\s,]+/).map(Number)
  if (m.length !== 4 || m[2] <= 0 || m[3] <= 0) return
  const vx0 = m[0], vy0 = m[1], vw0 = m[2], vh0 = m[3]

  wrapper.dataset.panzoomBound = 'true'
  wrapper.classList.add('interactive')

  // scale 为缩放倍数（相对初始视图）；panX/panY 为 SVG 坐标系平移量
  let scale = 1, panX = 0, panY = 0
  let dragging = false, sx = 0, sy = 0, sPanX = 0, sPanY = 0
  const MIN = 0.3, MAX = 6

  // 将当前缩放/平移写入 viewBox：viewBox 宽高随 scale 反比缩放
  function applyViewBox() {
    const vw = vw0 / scale
    const vh = vh0 / scale
    svg.setAttribute('viewBox', (vx0 - panX) + ' ' + (vy0 - panY) + ' ' + vw + ' ' + vh)
  }
  // 限制平移范围：保证 viewBox 与原始内容区域始终有交集，避免图表被完全拖出可视区
  function clampPan() {
    const vw = vw0 / scale
    const vh = vh0 / scale
    panX = Math.min(vw, Math.max(-vw0, panX))
    panY = Math.min(vh, Math.max(-vh0, panY))
  }
  // 屏幕像素坐标转 SVG 坐标（用 getScreenCTM 精确转换，兼容 meet 留白）
  function screenToSvg(clientX, clientY) {
    const ctm = svg.getScreenCTM()
    if (!ctm) return [vx0, vy0]
    const inv = ctm.inverse()
    return [
      clientX * inv.a + clientY * inv.c + inv.e,
      clientX * inv.b + clientY * inv.d + inv.f
    ]
  }
  // 以 SVG 坐标 (cx, cy) 为中心缩放到 newScale，保持该点在视口中位置不变
  function zoomAt(newScale, cx, cy) {
    newScale = Math.min(MAX, Math.max(MIN, newScale))
    if (newScale === scale) return
    const curVx = vx0 - panX, curVy = vy0 - panY
    const curVw = vw0 / scale, curVh = vh0 / scale
    const fx = (cx - curVx) / curVw
    const fy = (cy - curVy) / curVh
    const newVw = vw0 / newScale, newVh = vh0 / newScale
    panX = vx0 - (cx - fx * newVw)
    panY = vy0 - (cy - fy * newVh)
    scale = newScale
    clampPan()
    applyViewBox()
  }
  function onWheel(e) {
    e.preventDefault()
    const [cx, cy] = screenToSvg(e.clientX, e.clientY)
    zoomAt(scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), cx, cy)
  }
  function onDown(e) {
    if (e.button !== 0) return
    dragging = true
    sx = e.clientX; sy = e.clientY; sPanX = panX; sPanY = panY
    wrapper.classList.add('dragging')
    e.preventDefault()
  }
  function onMove(e) {
    if (!wrapper.isConnected) {
      document.removeEventListener('mousemove', onMove)
      document.removeEventListener('mouseup', onUp)
      return
    }
    if (!dragging) return
    const ctm = svg.getScreenCTM()
    if (!ctm || ctm.a === 0) return
    // 像素位移转 SVG 坐标位移（ctm.a/d 为 svg->屏幕的缩放系数）
    const dxSvg = (e.clientX - sx) / ctm.a
    const dySvg = (e.clientY - sy) / ctm.d
    // viewBox x = vx0 - panX，panX 与位移同向（鼠标左拖 dx<0 -> panX 减小 -> viewBox x 增大 -> 内容左移）
    panX = sPanX + dxSvg
    panY = sPanY + dySvg
    clampPan()
    applyViewBox()
  }
  function onUp() {
    if (!dragging) return
    dragging = false
    wrapper.classList.remove('dragging')
  }
  function reset() {
    scale = 1; panX = 0; panY = 0
    applyViewBox()
  }

  wrapper.addEventListener('wheel', onWheel, { passive: false })
  wrapper.addEventListener('mousedown', onDown)
  wrapper.addEventListener('dblclick', reset)
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
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
function detectDiagramType(code) {
  if (!code) return 'graph'
  const first = code.trim().split('\n')[0].trim()
  const m = first.match(/^(graph|flowchart|sequenceDiagram|classDiagram|stateDiagram|stateDiagram-v2|gantt|pie|gitGraph|erDiagram|journey|mindmap|timeline|quadrantChart|requirementDiagram|C4Context|C4Container|C4Component|C4Dynamic|sankey|block|packet|architecture)\b/i)
  if (m) return m[1].toLowerCase()
  return 'graph'
}

function getDiagramIcon(typeKey) {
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
function copyTextWithFallback(text, onSuccess, onFail) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(onSuccess).catch(() => {
      fallbackCopy(text) ? onSuccess() : onFail()
    })
  } else {
    fallbackCopy(text) ? onSuccess() : onFail()
  }
}

// execCommand 降级复制
function fallbackCopy(text) {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.style.position = 'fixed'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch (e) { return false }
}

// 轻量 toast 提示（不依赖组件库，2.5 秒后自动移除）
function showToast(msg) {
  let el = document.getElementById('__md_toast')
  if (!el) {
    el = document.createElement('div')
    el.id = '__md_toast'
    el.style.cssText = 'position:fixed;left:50%;top:24px;transform:translateX(-50%);z-index:9999;' +
      'padding:8px 16px;border-radius:6px;background:rgba(30,30,46,0.92);color:#eee;' +
      'font-size:13px;box-shadow:0 4px 16px rgba(0,0,0,0.3);pointer-events:none;opacity:0;transition:opacity .2s'
    document.body.appendChild(el)
  }
  el.textContent = msg
  requestAnimationFrame(() => { el.style.opacity = '1' })
  clearTimeout(el.__t)
  el.__t = setTimeout(() => { el.style.opacity = '0' }, 2500)
}

// 显示 mermaid 主题切换菜单（每图独立切换，点击外部关闭）
function showMermaidThemeMenu(btn, container) {
  if (!container) return
  const existing = container.querySelector('.mermaid-theme-menu')
  if (existing) { existing.remove(); return }
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
  menu.querySelectorAll('.mermaid-theme-menu-item').forEach(item => {
    item.addEventListener('click', () => {
      applyMermaidTheme(container, item.getAttribute('data-theme'))
      menu.remove()
    })
  })
  setTimeout(() => {
    const onDocClick = (e) => {
      if (menu.contains(e.target) || btn.contains(e.target)) return
      menu.remove()
      document.removeEventListener('click', onDocClick)
    }
    document.addEventListener('click', onDocClick)
  }, 0)
}

// 显示 mermaid 下载选项菜单（SVG/PNG），点击选项触发对应下载，点击外部关闭
function showMermaidDownloadMenu(btn, container) {
  if (!container) return
  const existing = container.querySelector('.mermaid-download-menu')
  if (existing) { existing.remove(); return }
  const menu = document.createElement('div')
  menu.className = 'mermaid-download-menu active'
  menu.innerHTML =
    '<div class="mermaid-download-menu-item" data-fmt="svg"><span>下载 SVG</span></div>' +
    '<div class="mermaid-download-menu-item" data-fmt="png"><span>下载 PNG</span></div>'
  const toolbar = container.querySelector('.mermaid-toolbar')
  if (toolbar) { toolbar.style.position = 'relative'; toolbar.appendChild(menu) }
  else { container.appendChild(menu) }
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
      menu.remove()
    })
  })
  setTimeout(() => {
    const onDocClick = (e) => {
      if (menu.contains(e.target) || btn.contains(e.target)) return
      menu.remove()
      document.removeEventListener('click', onDocClick)
    }
    document.addEventListener('click', onDocClick)
  }, 0)
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

// 导出前清理：剥离内联样式（width:100%/transform等），按 viewBox 还原自然尺寸
function cleanSvgForExport(svg) {
  const clone = svg.cloneNode(true)
  clone.removeAttribute('style')
  clone.removeAttribute('class')
  const vb = clone.getAttribute('viewBox')
  if (vb) {
    const p = vb.split(/[\s,]+/).map(Number)
    if (p.length === 4 && p[2] > 0 && p[3] > 0) {
      clone.setAttribute('width', p[2])
      clone.setAttribute('height', p[3])
    }
  }
  return clone
}

// 将 SVG 内所有 <image> 的外部图片引用内联为 data URL，
// 避免 SVG 经 img 绘制到 canvas 后因跨域资源污染导致 toBlob 失败
async function inlineExternalImages(svg) {
  const images = Array.from(svg.querySelectorAll('image'))
  await Promise.all(images.map(img => (async () => {
    const href = img.getAttribute('href') || img.getAttributeNS('http://www.w3.org/1999/xlink', 'href') || img.getAttribute('xlink:href')
    if (!href || href.startsWith('data:') || href.startsWith('#')) return
    try {
      const res = await fetch(href, { mode: 'cors' })
      if (!res.ok) return
      const blob = await res.blob()
      const dataUrl = await new Promise((resolve, reject) => {
        const r = new FileReader()
        r.onload = () => resolve(r.result)
        r.onerror = reject
        r.readAsDataURL(blob)
      })
      img.setAttribute('href', dataUrl)
      img.removeAttributeNS('http://www.w3.org/1999/xlink', 'href')
      img.removeAttribute('xlink:href')
    } catch (e) { /* 跨域 fetch 失败则跳过，保留原引用 */ }
  })()))
}

function downloadFile(content, filename, type) {
  const blob = new Blob([content], { type })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

async function svgToPng(svgElement, filename) {
  // 先内联外部图片，避免 canvas 跨域污染导致 toBlob 失败
  await inlineExternalImages(svgElement)
  const svgData = new XMLSerializer().serializeToString(svgElement)
  const blob = new Blob([svgData], { type: 'image/svg+xml;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const img = new Image()
  img.onload = function () {
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d')
    const w = img.width || svgElement.clientWidth || 800
    const h = img.height || svgElement.clientHeight || 600
    canvas.width = w * 2
    canvas.height = h * 2
    ctx.scale(2, 2)
    ctx.drawImage(img, 0, 0, w, h)
    URL.revokeObjectURL(url)
    try {
      canvas.toBlob(pngBlob => {
        if (!pngBlob) { showToast('PNG 导出失败，请改用 SVG 下载'); return }
        const pngUrl = URL.createObjectURL(pngBlob)
        const a = document.createElement('a')
        a.href = pngUrl
        a.download = filename
        a.click()
        setTimeout(() => URL.revokeObjectURL(pngUrl), 1000)
      }, 'image/png')
    } catch (e) {
      // canvas 被跨域资源污染，toBlob 抛 SecurityError
      showToast('PNG 导出失败（图表含跨域资源），请改用 SVG 下载')
    }
  }
  img.onerror = function () {
    URL.revokeObjectURL(url)
    showToast('PNG 导出失败，请改用 SVG 下载')
  }
  img.src = url
}

function openMermaidFullscreen(svg) {
  // 解析裁剪后的初始 viewBox（由 cropSvgViewBox 写入），无则无法缩放
  const vbStr = svg.getAttribute('viewBox')
  if (!vbStr) return
  const parts = vbStr.split(/[\s,]+/).map(Number)
  if (parts.length !== 4 || parts[2] <= 0 || parts[3] <= 0) return
  const vx0 = parts[0], vy0 = parts[1], vw0 = parts[2], vh0 = parts[3]

  // --- 状态 ---
  // zoom 为缩放倍数（相对初始适配视图，1 = meet 自动适配视口）；
  // panX/panY 为 SVG 坐标系平移量。采用 viewBox 缩放保证矢量清晰。
  let zoom = 1
  let panX = 0, panY = 0
  let dragging = false
  let startX = 0, startY = 0, sPanX = 0, sPanY = 0
  let rafId = null
  const MIN_ZOOM = 0.2, MAX_ZOOM = 8

  // --- DOM 构建 ---
  const overlay = document.createElement('div')
  overlay.className = 'mermaid-fullscreen-overlay'

  // 顶部工具栏
  const toolbar = document.createElement('div')
  toolbar.className = 'mermaid-fullscreen-toolbar'
  toolbar.innerHTML =
    `<button class="mfs-btn" data-mfs="zoomOut" title="缩小"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="8" y1="11" x2="14" y2="11"/></svg></button>` +
    `<span class="mfs-zoom-label">100%</span>` +
    `<button class="mfs-btn" data-mfs="zoomIn" title="放大"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/><line x1="11" y1="8" x2="11" y2="14"/><line x1="8" y1="11" x2="14" y2="11"/></svg></button>` +
    `<button class="mfs-btn" data-mfs="reset" title="重置视图"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8"/><path d="M3 3v5h5"/></svg></button>` +
    `<span class="mfs-divider"></span>` +
    `<button class="mfs-btn" data-mfs="downloadSvg" title="下载 SVG"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>` +
    `<button class="mfs-btn" data-mfs="downloadPng" title="下载图片"><svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="18" height="18" rx="2" ry="2"/><circle cx="8.5" cy="8.5" r="1.5"/><path d="M21 15l-5-5L5 21"/></svg></button>`

  // 关闭按钮（右上角）
  const closeBtn = document.createElement('button')
  closeBtn.className = 'mermaid-fullscreen-close'
  closeBtn.innerHTML = '<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>'

  // 画布区域
  const content = document.createElement('div')
  content.className = 'mermaid-fullscreen-content'
  const svgClone = svg.cloneNode(true)
  svgClone.removeAttribute('style')
  // 移除固定宽高，仅保留 viewBox；CSS 设 width/height:100% 占满画布，
  // preserveAspectRatio="xMidYMid meet" 自动适配视口（大则缩小，小则原大小居中）
  svgClone.removeAttribute('width')
  svgClone.removeAttribute('height')
  svgClone.setAttribute('preserveAspectRatio', 'xMidYMid meet')
  svgClone.classList.add('mfs-svg')
  content.appendChild(svgClone)

  overlay.appendChild(toolbar)
  overlay.appendChild(closeBtn)
  overlay.appendChild(content)

  // --- 变换应用（修改 viewBox，矢量缩放不模糊） ---
  const zoomLabel = toolbar.querySelector('.mfs-zoom-label')
  function applyViewBox() {
    const vw = vw0 / zoom
    const vh = vh0 / zoom
    svgClone.setAttribute('viewBox', (vx0 - panX) + ' ' + (vy0 - panY) + ' ' + vw + ' ' + vh)
    zoomLabel.textContent = Math.round(zoom * 100) + '%'
  }
  // 屏幕像素坐标转 SVG 坐标（用 getScreenCTM 精确转换，兼容 meet 留白）
  function screenToSvg(clientX, clientY) {
    const ctm = svgClone.getScreenCTM()
    if (!ctm) return [vx0 + vw0 / 2, vy0 + vh0 / 2]
    const inv = ctm.inverse()
    return [
      clientX * inv.a + clientY * inv.c + inv.e,
      clientX * inv.b + clientY * inv.d + inv.f
    ]
  }
  // 直接设置缩放并保持中心点 (cx, cy) 视口位置不变
  function setZoom(newZoom, cx, cy) {
    newZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, newZoom))
    if (newZoom === zoom) { applyViewBox(); return }
    const curVx = vx0 - panX, curVy = vy0 - panY
    const curVw = vw0 / zoom, curVh = vh0 / zoom
    const fx = (cx - curVx) / curVw
    const fy = (cy - curVy) / curVh
    const newVw = vw0 / newZoom, newVh = vh0 / newZoom
    panX = vx0 - (cx - fx * newVw)
    panY = vy0 - (cy - fy * newVh)
    zoom = newZoom
    applyViewBox()
  }
  // 以 SVG 坐标 (cx, cy) 为中心缩放，animate=true 时用 rAF 做缓动过渡
  function zoomAt(targetZoom, cx, cy, animate) {
    targetZoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, targetZoom))
    if (rafId) { cancelAnimationFrame(rafId); rafId = null }
    if (!animate || targetZoom === zoom) { setZoom(targetZoom, cx, cy); return }
    const fromZoom = zoom
    const startT = performance.now()
    const dur = 200
    function step(now) {
      const t = Math.min(1, (now - startT) / dur)
      // easeInOutQuad 缓动
      const ease = t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2
      setZoom(fromZoom + (targetZoom - fromZoom) * ease, cx, cy)
      if (t < 1) rafId = requestAnimationFrame(step)
      else rafId = null
    }
    rafId = requestAnimationFrame(step)
  }

  function resetView() {
    if (rafId) { cancelAnimationFrame(rafId); rafId = null }
    zoom = 1; panX = 0; panY = 0
    applyViewBox()
  }

  // --- 滚轮缩放 ---
  function onWheel(e) {
    e.preventDefault()
    const [cx, cy] = screenToSvg(e.clientX, e.clientY)
    const factor = e.deltaY < 0 ? 1.15 : 1 / 1.15
    zoomAt(zoom * factor, cx, cy, false)
  }

  // --- 拖拽平移 ---
  function onMouseDown(e) {
    if (e.button !== 0) return
    if (rafId) { cancelAnimationFrame(rafId); rafId = null }
    dragging = true
    startX = e.clientX; startY = e.clientY
    sPanX = panX; sPanY = panY
    content.classList.add('dragging')
    e.preventDefault()
  }
  function onMouseMove(e) {
    if (!dragging) return
    const ctm = svgClone.getScreenCTM()
    if (!ctm || ctm.a === 0) return
    // 像素位移转 SVG 坐标位移（ctm.a/d 为 svg->屏幕的缩放系数）
    const dxSvg = (e.clientX - startX) / ctm.a
    const dySvg = (e.clientY - startY) / ctm.d
    // viewBox x = vx0 - panX，panX 与位移同向（鼠标左拖 dx<0 -> panX 减小 -> viewBox x 增大 -> 内容左移）
    panX = sPanX + dxSvg
    panY = sPanY + dySvg
    applyViewBox()
  }
  function onMouseUp() {
    if (!dragging) return
    dragging = false
    content.classList.remove('dragging')
  }

  // --- 工具栏事件 ---
  function onToolbarClick(e) {
    const btn = e.target.closest('[data-mfs]')
    if (!btn) return
    const act = btn.getAttribute('data-mfs')
    // 工具栏按钮以画布中心为缩放中心
    const rect = svgClone.getBoundingClientRect()
    const [cx, cy] = screenToSvg(rect.left + rect.width / 2, rect.top + rect.height / 2)
    switch (act) {
      case 'zoomIn': zoomAt(zoom * 1.3, cx, cy, true); break
      case 'zoomOut': zoomAt(zoom / 1.3, cx, cy, true); break
      case 'reset': resetView(); break
      case 'downloadSvg': {
        const svgString = new XMLSerializer().serializeToString(cleanSvgForExport(svg))
        downloadFile(svgString, 'mermaid-' + Date.now() + '.svg', 'image/svg+xml')
        break
      }
      case 'downloadPng': svgToPng(cleanSvgForExport(svg), 'mermaid-' + Date.now() + '.png'); break
    }
  }

  // --- 关闭与清理 ---
  function close() {
    if (rafId) cancelAnimationFrame(rafId)
    overlay.remove()
    document.removeEventListener('keydown', onKeyDown)
    document.removeEventListener('mousemove', onMouseMove)
    document.removeEventListener('mouseup', onMouseUp)
    content.removeEventListener('wheel', onWheel)
  }
  function onKeyDown(e) {
    if (e.key === 'Escape') close()
    else if (e.key === '+' || e.key === '=') {
      const r = svgClone.getBoundingClientRect()
      const [cx, cy] = screenToSvg(r.left + r.width / 2, r.top + r.height / 2)
      zoomAt(zoom * 1.3, cx, cy, true)
    }
    else if (e.key === '-') {
      const r = svgClone.getBoundingClientRect()
      const [cx, cy] = screenToSvg(r.left + r.width / 2, r.top + r.height / 2)
      zoomAt(zoom / 1.3, cx, cy, true)
    }
    else if (e.key === '0') resetView()
  }

  closeBtn.addEventListener('click', close)
  toolbar.addEventListener('click', onToolbarClick)
  content.addEventListener('mousedown', onMouseDown)
  content.addEventListener('wheel', onWheel, { passive: false })
  document.addEventListener('mousemove', onMouseMove)
  document.addEventListener('mouseup', onMouseUp)
  document.addEventListener('keydown', onKeyDown)
  // 双击重置
  content.addEventListener('dblclick', resetView)

  document.body.appendChild(overlay)

  // 初始：zoom=1，meet 自动适配视口，无需手动计算适配比例
  applyViewBox()
}

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

export function escapeHtml(str) {
  const div = document.createElement('div')
  div.textContent = str
  return div.innerHTML
}

export function renderMarkdown(text) {
  if (!text) return ''

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

  let html = marked.parse(text)

  // 还原mermaid块（带工具栏卡片）
  mermaidBlocks.forEach((code, idx) => {
    const escaped = escapeHtml(code)
    const typeKey = detectDiagramType(code)
    const typeLabel = typeKey.replace(/diagram$/i, '').toUpperCase()
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
      `<div class="mermaid-scroll-wrapper"><div class="mermaid-view"><pre class="mermaid">${escaped}</pre></div></div>` +
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
  return DOMPurify.sanitize(html, { ADD_ATTR: ['target'], ADD_TAGS: ['use'] })
}

// 下载媒体文件（图片/视频）：fetch 转 blob 触发下载，跨域失败回退直接打开链接
function downloadMedia(url, filename) {
  fetch(url).then(r => r.blob()).then(blob => {
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = filename
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    setTimeout(() => URL.revokeObjectURL(a.href), 1000)
  }).catch(() => {
    window.open(url, '_blank')
  })
}

// 处理特殊内容：代码高亮、表格包裹、AI 图片/视频增强
export function processSpecialContent(container) {
  if (!container) return

  // 表格包装
  container.querySelectorAll('.msg-bubble table, .answer-content table').forEach(table => {
    if (table.parentElement.classList.contains('table-wrapper')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'table-wrapper'
    table.parentNode.insertBefore(wrapper, table)
    wrapper.appendChild(table)
    if (table.offsetWidth > wrapper.offsetWidth) {
      wrapper.classList.add('has-overflow')
    }
    wrapper.addEventListener('scroll', function () {
      const isScrolledToEnd = this.scrollLeft + this.clientWidth >= this.scrollWidth - 2
      this.classList.toggle('has-overflow', !isScrolledToEnd)
    })
  })

  // 代码块高亮
  container.querySelectorAll('pre code').forEach(block => {
    if (block.dataset.processed) return
    block.dataset.processed = 'true'
    const lang = (block.className.match(/language-(\w+)/) || ['', ''])[1]
    if (lang === 'mermaid' || lang === 'mer' || lang === 'mmd') return
    try {
      hljs.highlightElement(block)
    } catch (e) { /* ignore */ }
    const pre = block.parentElement
    if (pre.querySelector('.code-header')) return
    const header = document.createElement('div')
    header.className = 'code-header'
    header.innerHTML = `<span>${lang || 'text'}</span><button class="code-copy-btn">复制代码</button>`
    pre.insertBefore(header, pre.firstChild)
    // 复制按钮事件（clipboard 失败回退 execCommand）
    header.querySelector('.code-copy-btn').addEventListener('click', function () {
      const self = this
      copyTextWithFallback(block.textContent, () => {
        self.textContent = '已复制!'
        setTimeout(() => { self.textContent = '复制代码' }, 2000)
      }, () => { showToast('复制失败') })
    })
  })

  // AI 生成图片增强：点击放大 + 下载工具栏（点击放大派发 lightbox 事件由 ChatMessages 转发到灯箱）
  container.querySelectorAll('.msg-bubble img:not(.user-msg-img):not([data-img-enhanced])').forEach(img => {
    img.setAttribute('data-img-enhanced', 'true')
    img.classList.add('ai-msg-img')
    img.style.cursor = 'pointer'
    img.addEventListener('click', () => {
      img.dispatchEvent(new CustomEvent('lightbox', { detail: { src: img.src }, bubbles: true }))
    })
    const wrapper = document.createElement('div')
    wrapper.className = 'ai-image-wrapper'
    img.parentNode.insertBefore(wrapper, img)
    wrapper.appendChild(img)
    const toolbar = document.createElement('div')
    toolbar.className = 'ai-image-toolbar'
    toolbar.innerHTML =
      '<button class="ai-img-btn" title="放大预览"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/></svg></button>' +
      '<button class="ai-img-btn" title="下载"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></button>'
    const [zoomBtn, dlBtn] = toolbar.querySelectorAll('button')
    zoomBtn.addEventListener('click', (e) => {
      e.stopPropagation()
      img.dispatchEvent(new CustomEvent('lightbox', { detail: { src: img.src }, bubbles: true }))
    })
    dlBtn.addEventListener('click', (e) => {
      e.stopPropagation()
      downloadMedia(img.src, 'ai-image-' + Date.now())
    })
    wrapper.appendChild(toolbar)
  })

  // AI 生成视频增强：原生控件 + 下载按钮
  container.querySelectorAll('.msg-bubble video:not([data-video-enhanced])').forEach(video => {
    video.setAttribute('data-video-enhanced', 'true')
    video.setAttribute('controls', 'true')
    video.setAttribute('controlslist', 'nodownload')
    video.setAttribute('preload', 'metadata')
    const wrapper = document.createElement('div')
    wrapper.className = 'ai-video-wrapper'
    video.parentNode.insertBefore(wrapper, video)
    wrapper.appendChild(video)
    const toolbar = document.createElement('div')
    toolbar.className = 'ai-video-toolbar'
    toolbar.innerHTML = '<button class="ai-vid-btn" title="下载视频"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> 下载</button>'
    toolbar.querySelector('button').addEventListener('click', (e) => {
      e.stopPropagation()
      downloadMedia(video.src, 'ai-video-' + Date.now())
    })
    wrapper.appendChild(toolbar)
  })
}

export function useMarkdown() {
  return { renderMarkdown, processSpecialContent, escapeHtml }
}
