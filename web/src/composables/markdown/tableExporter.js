import { downloadFile, showToast } from '@/composables/markdown/domActions'
import { loadXlsxExporter } from '@/composables/markdown/lazyDependencies'
import { onElementDetached } from '@/composables/markdown/domLifecycle'

const DOWNLOAD_ICON = '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>'
const FULLSCREEN_ICON = '<polyline points="15 3 21 3 21 9"/><polyline points="9 21 3 21 3 15"/><line x1="21" y1="3" x2="14" y2="10"/><line x1="3" y1="21" x2="10" y2="14"/>'
const CLOSE_ICON = '<path d="m18 6-12 12M6 6l12 12"/>'
let menuSequence = 0

/** 包裹 Markdown 表格并挂载溢出状态和顶部工具栏。 */
export function enhanceMarkdownTables(container) {
  container.querySelectorAll('.msg-bubble table, .answer-content table').forEach(table => {
    if (table.closest('.md-table-header-scroll')) return
    if (table.parentElement.classList.contains('table-wrapper')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'table-wrapper'
    table.parentNode.insertBefore(wrapper, table)
    wrapper.appendChild(table)
    if (table.offsetWidth > wrapper.offsetWidth) wrapper.classList.add('has-overflow')
    wrapper.addEventListener('scroll', function () {
      const atEnd = this.scrollLeft + this.clientWidth >= this.scrollWidth - 2
      this.classList.toggle('has-overflow', !atEnd)
    })
    attachTableDownload(wrapper, table)
  })
}

/**
 * 为 Markdown 表格挂载顶部工具栏，工具栏不随表格横向滚动。
 */
export function attachTableDownload(wrapper, table) {
  const card = document.createElement('div')
  card.className = 'md-table-container'
  wrapper.parentNode.insertBefore(card, wrapper)
  const toolbar = createTableToolbar(table)
  const fullscreenButton = createTableButton('全屏查看表格', FULLSCREEN_ICON)
  fullscreenButton.addEventListener('click', () => openTableFullscreen(table, fullscreenButton))
  toolbar.appendChild(fullscreenButton)
  const stickyGroup = document.createElement('div')
  stickyGroup.className = 'md-table-sticky-group'
  stickyGroup.appendChild(toolbar)
  card.append(stickyGroup, wrapper)
}

/** 创建带可访问名称和悬停提示的纯图标按钮。 */
function createTableButton(label, icon) {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'md-table-btn'
  button.title = label
  button.setAttribute('aria-label', label)
  button.innerHTML = `<svg aria-hidden="true" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">${icon}</svg>`
  return button
}

/** 创建内联与全屏视图共用的下载工具栏。 */
function createTableToolbar(table) {
  const toolbar = document.createElement('div')
  toolbar.className = 'md-table-toolbar'
  const button = createTableButton('下载表格', DOWNLOAD_ICON)
  button.setAttribute('aria-expanded', 'false')
  button.setAttribute('aria-controls', `md-table-menu-${++menuSequence}`)
  button.addEventListener('click', event => {
    event.stopPropagation()
    showTableDownloadMenu(toolbar, button, table)
  })
  toolbar.appendChild(button)
  return toolbar
}

/** 使用原生模态对话框展示表格副本，保留原表格位置并管理键盘焦点。 */
function openTableFullscreen(table, trigger) {
  const dialog = document.createElement('dialog')
  dialog.className = 'md-table-fullscreen'
  dialog.setAttribute('aria-label', '全屏查看表格')
  const content = document.createElement('div')
  content.className = 'md-table-fullscreen-content msg-bubble'
  content.tabIndex = 0
  content.setAttribute('role', 'region')
  content.setAttribute('aria-label', '表格内容，可滚动查看')
  const clone = table.cloneNode(true)
  // 副本不重复原表格中的 ID，避免干扰聊天页面的锚点与标签关联。
  clone.removeAttribute('id')
  clone.querySelectorAll('[id]').forEach(element => element.removeAttribute('id'))
  content.appendChild(clone)
  const toolbar = createTableToolbar(clone)
  const closeButton = createTableButton('退出全屏', CLOSE_ICON)
  toolbar.appendChild(closeButton)
  dialog.append(toolbar, content)
  document.body.appendChild(dialog)

  let closed = false
  const cancelSourceWatch = onElementDetached(table, close)
  const cancelDialogWatch = onElementDetached(dialog, close)
  /** 关闭弹层并释放观察器、下载菜单监听，恢复触发按钮焦点。 */
  function close() {
    if (closed) return
    closed = true
    cancelSourceWatch()
    cancelDialogWatch()
    const menu = toolbar.querySelector('.md-table-menu')
    if (menu) closeTableDownloadMenu(menu)
    if (dialog.open) dialog.close()
    dialog.remove()
    if (trigger.isConnected) trigger.focus({ preventScroll: true })
  }
  closeButton.addEventListener('click', close)
  dialog.addEventListener('cancel', event => {
    event.preventDefault()
    close()
  })
  dialog.addEventListener('close', close)
  dialog.addEventListener('keydown', trapTableDialogFocus)
  dialog.showModal()
  closeButton.focus({ preventScroll: true })
}

/** 在首尾控件间循环 Tab，避免原生模态对话框把焦点移到浏览器工具栏。 */
function trapTableDialogFocus(event) {
  if (event.key !== 'Tab') return
  const controls = Array.from(event.currentTarget.querySelectorAll(
    'button, a[href], input, select, textarea, [tabindex]'
  )).filter(element => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length)
  const first = controls[0]
  const last = controls[controls.length - 1]
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus({ preventScroll: true })
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus({ preventScroll: true })
  }
}

/**
 * 提取表格单元格文本为二维数组，供下载与单元测试复用。
 */
export function extractTableRows(table) {
  const rows = []
  table.querySelectorAll('tr').forEach(row => {
    const cells = Array.from(row.querySelectorAll('th, td')).map(cell => (cell.innerText || '').trim())
    if (cells.length && cells.some(value => value !== '')) rows.push(cells)
  })
  return rows
}

/** 将二维表格数据序列化为兼容 Excel 的 CSV 文本。 */
export function serializeTableCsv(rows) {
  return rows.map(columns => columns.map(serializeCsvCell).join(',')).join('\r\n')
}

/** 转义单个 CSV 单元格，并阻止电子表格将不可信文本解释为公式。 */
function serializeCsvCell(value) {
  const text = String(value ?? '')
  const formulaCandidate = text.replace(/^[\t\r ]+/, '')
  const safeText = isUnsafeFormula(formulaCandidate) ? `'${text}` : text
  return /[",\n\r]/.test(safeText) ? `"${safeText.replace(/"/g, '""')}"` : safeText
}

/** 判断文本是否可能被 Excel 等软件作为公式执行，同时保留普通负数。 */
function isUnsafeFormula(value) {
  if (/^[=+@]/.test(value)) return true
  return value.startsWith('-') && !/^-\d+(?:\.\d+)?$/.test(value)
}

/** 展示 Excel/CSV 格式菜单，并注册一次性外部点击关闭逻辑。 */
function showTableDownloadMenu(toolbar, button, table) {
  const existing = toolbar.querySelector('.md-table-menu')
  if (existing) {
    closeTableDownloadMenu(existing)
    return
  }
  const menu = document.createElement('div')
  menu.className = 'md-table-menu'
  menu.id = button.getAttribute('aria-controls')
  menu.innerHTML = '<button type="button" class="md-table-menu-item" data-fmt="xlsx">下载 Excel (.xlsx)</button>'
    + '<button type="button" class="md-table-menu-item" data-fmt="csv">下载 CSV</button>'
  toolbar.appendChild(menu)
  button.setAttribute('aria-expanded', 'true')
  menu.__trigger = button
  menu.addEventListener('keydown', event => {
    if (event.key !== 'Escape') return
    event.preventDefault()
    event.stopPropagation()
    closeTableDownloadMenu(menu)
    button.focus({ preventScroll: true })
  })
  menu.querySelectorAll('.md-table-menu-item').forEach(item => {
    item.addEventListener('click', () => {
      const format = item.getAttribute('data-fmt')
      const rows = extractTableRows(table)
      closeTableDownloadMenu(menu)
      button.focus({ preventScroll: true })
      if (!rows.length) {
        showToast('表格内容为空')
        return
      }
      if (format === 'csv') downloadTableCsv(rows)
      else downloadTableXlsx(rows)
    })
  })
  menu.__removeOutsideClick = registerOutsideClick(menu, button)
  menu.querySelector('button').focus({ preventScroll: true })
}

/** 关闭菜单并同步释放 document 级监听。 */
function closeTableDownloadMenu(menu) {
  const removeOutsideClick = menu.__removeOutsideClick
  menu.__removeOutsideClick = null
  if (removeOutsideClick) removeOutsideClick()
  menu.__trigger?.setAttribute('aria-expanded', 'false')
  menu.remove()
}

/** 注册菜单外部点击关闭监听。 */
function registerOutsideClick(menu, button) {
  const onDocumentClick = event => {
    if (menu.contains(event.target) || button.contains(event.target)) return
    closeTableDownloadMenu(menu)
  }
  /** 焦点移出下载按钮与菜单时收起菜单，支持纯键盘导航。 */
  const onFocusOut = event => {
    if (menu.contains(event.relatedTarget) || button === event.relatedTarget) return
    closeTableDownloadMenu(menu)
  }
  document.addEventListener('click', onDocumentClick)
  menu.addEventListener('focusout', onFocusOut)
  const cancelDetachWatch = onElementDetached(menu, () => closeTableDownloadMenu(menu))
  return () => {
    cancelDetachWatch()
    document.removeEventListener('click', onDocumentClick)
    menu.removeEventListener('focusout', onFocusOut)
  }
}

/** 下载 CSV 文件。 */
function downloadTableCsv(rows) {
  downloadFile('\ufeff' + serializeTableCsv(rows), `table-${Date.now()}.csv`, 'text/csv;charset=utf-8')
}

/** 使用按需加载的 SheetJS 下载 Excel 文件。 */
async function downloadTableXlsx(rows) {
  try {
    const XLSX = await loadXlsxExporter()
    const values = rows.map(columns => columns.map(value =>
      /^-?(0|[1-9]\d*)(\.\d+)?$/.test(value) ? Number(value) : value
    ))
    const sheet = XLSX.utils.aoa_to_sheet(values)
    sheet['!cols'] = calculateColumnWidths(rows)
    const workbook = XLSX.utils.book_new()
    XLSX.utils.book_append_sheet(workbook, sheet, 'Sheet1')
    XLSX.writeFile(workbook, `table-${Date.now()}.xlsx`)
  } catch (error) {
    console.warn('[tableExporter] xlsx export failed:', error?.message || error)
    showToast('Excel 导出失败，请改用 CSV 下载')
  }
}

/** 根据中英文显示宽度计算 Excel 列宽。 */
function calculateColumnWidths(rows) {
  const columnCount = Math.max(...rows.map(row => row.length))
  const columns = []
  for (let column = 0; column < columnCount; column++) {
    let width = 6
    rows.forEach(row => {
      const value = String(row[column] == null ? '' : row[column])
      let length = 0
      for (const character of value) {
        length += /[\u2e80-\u9fff\uf900-\ufaff\uff00-\uffef]/.test(character) ? 2 : 1
      }
      width = Math.max(width, length)
    })
    columns.push({ wch: Math.min(width + 2, 50) })
  }
  return columns
}
