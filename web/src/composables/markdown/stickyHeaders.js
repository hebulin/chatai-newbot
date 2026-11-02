import { onElementDetached } from './domLifecycle'

const viewports = new WeakMap()
const registeredCards = new WeakSet()

/** 注册富文本卡片；表格工具栏与可视表头共用一个原生 sticky 容器，不用 JS 跟随纵向滚动。 */
export function enhanceStickyHeaders(container) {
  container.querySelectorAll('.md-table-container, pre.md-code-block, .mermaid-container').forEach(card => {
    if (registeredCards.has(card) || !card.isConnected) return
    const root = findScrollViewport(card)
    if (!root) return
    let viewport = viewports.get(root)
    if (!viewport) {
      viewport = createStickyViewport(root)
      viewports.set(root, viewport)
    }
    viewport.add(card)
    card.querySelectorAll('.mermaid-toolbar button[title]').forEach(button => {
      button.setAttribute('aria-label', button.title)
      button.querySelector('svg')?.setAttribute('aria-hidden', 'true')
    })
    registeredCards.add(card)
  })
}

/** 从卡片外侧寻找纵向滚动容器，避开表格自身的横向滚动层。 */
function findScrollViewport(card) {
  for (let parent = card.parentElement; parent; parent = parent.parentElement) {
    if (/(auto|scroll)/.test(getComputedStyle(parent).overflowY)) return parent
  }
  return null
}

/** 创建不参与辅助技术和键盘导航的可视表头，原表格仍是唯一的数据与语义来源。 */
function createTableHeader(card) {
  const wrapper = card.querySelector('.table-wrapper')
  const table = wrapper?.querySelector('table')
  if (!table?.tHead) return null
  const viewport = document.createElement('div')
  viewport.className = 'md-table-header-scroll'
  viewport.setAttribute('aria-hidden', 'true')
  viewport.setAttribute('inert', '')
  const copy = document.createElement('table')
  viewport.appendChild(copy)
  card.querySelector('.md-table-sticky-group').appendChild(viewport)
  /** 横向滚动直接同步，不再排队到下一动画帧；纵向位置完全由原生 sticky 处理。 */
  const syncHorizontal = () => { viewport.scrollLeft = wrapper.scrollLeft }
  wrapper.addEventListener('scroll', syncHorizontal, { passive: true })
  return { table, head: table.tHead, viewport, copy, markup: null, syncHorizontal,
    cleanup: () => wrapper.removeEventListener('scroll', syncHorizontal) }
}

/** 同一视口共享尺寸与内容观察器，仅在布局或内容变化时更新表头副本。 */
function createStickyViewport(root) {
  const entries = new Map()
  const obstruction = root.querySelector(':scope > .share-top')
  let frame = null
  let disposed = false
  let lastInset = null
  const originalInset = root.style.getPropertyValue('--markdown-sticky-top')
  const resize = new ResizeObserver(schedule)
  const mutations = new MutationObserver(records => {
    // 副本的更新不反过来触发新一轮复制。
    if (records.every(record => (record.target.nodeType === 1 ? record.target : record.target.parentElement)
      ?.closest('.md-table-header-scroll'))) return
    for (const [card, entry] of entries) {
      if (root.contains(card)) continue
      resize.unobserve(card)
      if (entry) {
        resize.unobserve(entry.head)
        entry.cleanup()
      }
      registeredCards.delete(card)
      entries.delete(card)
    }
    if (!entries.size) dispose()
    else schedule()
  })
  const cancelDetach = onElementDetached(root, dispose)
  window.addEventListener('resize', schedule, { passive: true })
  resize.observe(root)
  if (obstruction) resize.observe(obstruction)
  mutations.observe(root, { childList: true, subtree: true, characterData: true })

  /** 合并流式内容和尺寸变化；滚动时不调度纵向位置更新。 */
  function schedule() {
    if (!disposed && frame === null) frame = requestAnimationFrame(update)
  }

  /** 先读取源表格尺寸，再统一写入副本，避免边测量边修改布局。 */
  function update() {
    frame = null
    const inset = (obstruction?.getBoundingClientRect().height || 0) - (parseFloat(getComputedStyle(root).paddingTop) || 0)
    const changes = []
    for (const [card, entry] of entries) {
      if (!entry || !entry.head.getClientRects().length) continue
      const bounds = entry.head.getBoundingClientRect()
      changes.push({ card, entry, height: bounds.height, width: entry.table.getBoundingClientRect().width,
        markup: entry.head.innerHTML,
        widths: Array.from(entry.head.querySelectorAll('th, td'), cell => cell.getBoundingClientRect().width) })
    }
    if (inset !== lastInset) {
      root.style.setProperty('--markdown-sticky-top', inset + 'px')
      lastInset = inset
    }
    changes.forEach(({ card, entry, height, width, markup, widths }) => {
      if (entry.markup !== markup) {
        const clone = entry.head.cloneNode(true)
        clone.removeAttribute('id')
        clone.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'))
        entry.copy.replaceChildren(clone)
        entry.markup = markup
      }
      entry.copy.style.width = width + 'px'
      entry.copy.style.minWidth = width + 'px'
      entry.copy.querySelectorAll('th, td').forEach((cell, index) => {
        cell.style.width = widths[index] + 'px'
        cell.style.boxSizing = 'border-box'
      })
      card.style.setProperty('--table-head-height', height + 'px')
      entry.syncHorizontal()
    })
  }

  /** 观察卡片和源表头，覆盖异步内容、换行、分栏、字体及视口变化。 */
  function add(card) {
    const entry = card.matches('.md-table-container') ? createTableHeader(card) : null
    entries.set(card, entry)
    resize.observe(card)
    if (entry) resize.observe(entry.head)
    schedule()
  }

  /** 会话切换或全部卡片移除时释放观察器、横向监听和待执行帧。 */
  function dispose() {
    if (disposed) return
    disposed = true
    if (frame !== null) cancelAnimationFrame(frame)
    window.removeEventListener('resize', schedule)
    resize.disconnect()
    mutations.disconnect()
    cancelDetach()
    entries.forEach((entry, card) => {
      entry?.cleanup()
      registeredCards.delete(card)
    })
    entries.clear()
    if (originalInset) root.style.setProperty('--markdown-sticky-top', originalInset)
    else root.style.removeProperty('--markdown-sticky-top')
    viewports.delete(root)
  }

  return { add }
}
