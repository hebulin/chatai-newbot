import { downloadFile, showToast } from './domActions'

// 导出前清理：剥离内联样式（width:100%/transform等），按 viewBox 还原自然尺寸
export function cleanSvgForExport(svg) {
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

/** 将已清理的 SVG 转成双倍分辨率 PNG 并下载。 */
export async function svgToPng(svgElement, filename) {
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

/** 打开 SVG 全屏查看器并管理缩放、导出与退出。 */
export function openMermaidFullscreen(svg) {
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
  /** 应用全屏画布缩放和平移并更新倍率文本。 */
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
    /** 按缓动进度推进一帧缩放动画。 */
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

  /** 取消动画并还原初始画布。 */
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
  /** 拖拽期间更新全屏画布的平移坐标。 */
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
  /** 结束全屏拖拽状态。 */
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
  /** 处理全屏退出、缩放与重置快捷键。 */
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
