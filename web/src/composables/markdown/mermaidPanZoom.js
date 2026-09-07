import { onElementDetached } from './domLifecycle'

// ===== 聊天区内联缩放/拖拽 =====
// 采用 viewBox 缩放方案：通过修改 SVG 的 viewBox 属性实现缩放/平移，
// 而非 CSS transform。viewBox 缩放改变 SVG 内部坐标系，所有内容（含
// foreignObject 内的 HTML 标签文字）按新坐标系矢量重渲染，避免 CSS
// transform + will-change 触发 GPU 合成层栅格化导致的放大模糊问题。
const panZoomBindings = new WeakMap()

/** 为当前 SVG 绑定内联缩放；主题重渲染替换 SVG 时释放旧绑定。 */
export function attachInlinePanZoom(preEl) {
  const svg = preEl.querySelector('svg')
  const wrapper = preEl.closest('.mermaid-scroll-wrapper')
  if (!svg || !wrapper) return
  const previous = panZoomBindings.get(wrapper)
  if (previous?.svg === svg) return
  previous?.dispose()
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
  let cancelDetachWatch = null

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
  /** 以指针位置为中心处理滚轮缩放。 */
  function onWheel(e) {
    e.preventDefault()
    const [cx, cy] = screenToSvg(e.clientX, e.clientY)
    zoomAt(scale * (e.deltaY < 0 ? 1.15 : 1 / 1.15), cx, cy)
  }
  /** 开始左键拖拽并注册本次拖拽的临时全局监听。 */
  function onDown(e) {
    if (e.button !== 0) return
    stopDrag()
    dragging = true
    sx = e.clientX; sy = e.clientY; sPanX = panX; sPanY = panY
    wrapper.classList.add('dragging')
    document.addEventListener('mousemove', onMove)
    document.addEventListener('mouseup', onUp)
    window.addEventListener('blur', stopDrag)
    cancelDetachWatch = onElementDetached(svg, stopDrag)
    e.preventDefault()
  }
  /** 将指针移动转换为 SVG 坐标平移。 */
  function onMove(e) {
    if (!wrapper.isConnected) {
      stopDrag()
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
  /** 结束鼠标拖拽并释放临时监听。 */
  function onUp() {
    stopDrag()
  }
  // 结束当前拖拽并立即释放 document 级监听，静置时不持有已脱离的 v-html 节点。
  function stopDrag() {
    dragging = false
    wrapper.classList.remove('dragging')
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    window.removeEventListener('blur', stopDrag)
    cancelDetachWatch?.()
    cancelDetachWatch = null
  }
  /** 恢复图表的初始 viewBox。 */
  function reset() {
    scale = 1; panX = 0; panY = 0
    applyViewBox()
  }

  wrapper.addEventListener('wheel', onWheel, { passive: false })
  wrapper.addEventListener('mousedown', onDown)
  wrapper.addEventListener('dblclick', reset)
  // 移除旧 SVG 的局部交互，防止切换主题后工具仍操作已脱离节点。
  function dispose() {
    stopDrag()
    wrapper.removeEventListener('wheel', onWheel)
    wrapper.removeEventListener('mousedown', onDown)
    wrapper.removeEventListener('dblclick', reset)
    delete wrapper.dataset.panzoomBound
    panZoomBindings.delete(wrapper)
  }
  panZoomBindings.set(wrapper, { svg, dispose })
}
