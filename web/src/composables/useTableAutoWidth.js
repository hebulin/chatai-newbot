/**
 * 表格列宽自适应工具：按列内最长内容计算列宽（canvas 测宽），
 * 上限默认 700px（约 50 个汉字 @14px），保证单行展示，超出部分省略。
 */
let measureCtx = null

function getCtx() {
  if (!measureCtx) {
    measureCtx = document.createElement('canvas').getContext('2d')
  }
  return measureCtx
}

// 与 el-table 单元格字体保持一致
const TABLE_FONT = '14px -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", "Microsoft YaHei", Arial, sans-serif'
// el-table 表头为加粗字体，需单独测宽避免表头被截断
const TABLE_HEADER_FONT = '600 14px -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", "Microsoft YaHei", Arial, sans-serif'
// 等宽字体列（模型ID/延迟/速度等 --mono 渲染的内容）
const TABLE_MONO_FONT = '12px "SF Mono", Menlo, Consolas, monospace'

// 50 个汉字宽度上限（14px 字号）
export const COL_MAX_WIDTH = 700

/**
 * 计算列宽
 * @param {Array} values 该列所有单元格的展示文本
 * @param {Object} options
 *   header 表头文本（按加粗字体参与测宽，保证表头不被截断）
 *   extra  额外宽度（图标、徽章内边距、排序箭头等占位）
 *   min    最小列宽
 *   max    最大列宽（默认 50 个汉字）
 *   mono   该列内容以等宽字体渲染（模型ID/延迟/速度等）
 * @returns {number} 列宽 px
 */
export function autoColWidth(values, options = {}) {
  const { header = '', extra = 0, min = 60, max = COL_MAX_WIDTH, mono = false } = options
  const ctx = getCtx()
  // 表头按加粗字体测宽
  ctx.font = TABLE_HEADER_FONT
  let w = header ? ctx.measureText(header).width : 0
  ctx.font = mono ? TABLE_MONO_FONT : TABLE_FONT
  for (const v of values || []) {
    if (v == null || v === '') continue
    const tw = ctx.measureText(String(v)).width
    if (tw > w) w = tw
  }
  // canvas 测宽与浏览器实际渲染存在微小误差，乘 1.06 安全系数兜底
  // 30 = 单元格左右 padding(24) + 边框与渲染余量
  return Math.max(min, Math.min(max, Math.ceil(w * 1.06) + 30 + extra))
}
