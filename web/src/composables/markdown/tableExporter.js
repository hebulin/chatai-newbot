import { downloadFile, showToast } from '@/composables/markdown/domActions'
import { loadXlsxExporter } from '@/composables/markdown/lazyDependencies'
import { onElementDetached } from '@/composables/markdown/domLifecycle'

/** 包裹 Markdown 表格并挂载溢出状态和下载工具栏。 */
export function enhanceMarkdownTables(container) {
  container.querySelectorAll('.msg-bubble table, .answer-content table').forEach(table => {
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
 * 为 Markdown 表格追加 Excel/CSV 下载工具栏。
 */
export function attachTableDownload(wrapper, table) {
  const toolbar = document.createElement('div')
  toolbar.className = 'md-table-toolbar'
  toolbar.innerHTML = '<button class="md-table-btn" title="下载表格"><svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg> 下载表格</button>'
  const button = toolbar.querySelector('button')
  button.addEventListener('click', event => {
    event.stopPropagation()
    showTableDownloadMenu(toolbar, button, table)
  })
  wrapper.parentNode.insertBefore(toolbar, wrapper.nextSibling)
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
  menu.innerHTML = '<div class="md-table-menu-item" data-fmt="xlsx"><span>下载 Excel (.xlsx)</span></div>'
    + '<div class="md-table-menu-item" data-fmt="csv"><span>下载 CSV</span></div>'
  toolbar.appendChild(menu)
  menu.querySelectorAll('.md-table-menu-item').forEach(item => {
    item.addEventListener('click', () => {
      const format = item.getAttribute('data-fmt')
      const rows = extractTableRows(table)
      closeTableDownloadMenu(menu)
      if (!rows.length) {
        showToast('表格内容为空')
        return
      }
      if (format === 'csv') downloadTableCsv(rows)
      else downloadTableXlsx(rows)
    })
  })
  setTimeout(() => {
    if (menu.isConnected) menu.__removeOutsideClick = registerOutsideClick(menu, button)
  }, 0)
}

/** 关闭菜单并同步释放 document 级监听。 */
function closeTableDownloadMenu(menu) {
  const removeOutsideClick = menu.__removeOutsideClick
  menu.__removeOutsideClick = null
  if (removeOutsideClick) removeOutsideClick()
  menu.remove()
}

/** 注册菜单外部点击关闭监听。 */
function registerOutsideClick(menu, button) {
  const onDocumentClick = event => {
    if (menu.contains(event.target) || button.contains(event.target)) return
    closeTableDownloadMenu(menu)
  }
  document.addEventListener('click', onDocumentClick)
  const cancelDetachWatch = onElementDetached(menu, () => closeTableDownloadMenu(menu))
  return () => {
    cancelDetachWatch()
    document.removeEventListener('click', onDocumentClick)
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
