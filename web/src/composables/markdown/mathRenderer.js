import { markRenderDependencyReady } from '@/composables/markdown/renderDependencyState'

// ===== KaTeX 懒加载 =====
// katex 主体 ~280KB + mhchem 扩展；仅当消息含公式时才加载。
// mhchem 为副作用模块（向 katex 实例注册 \ce/\pu 命令），须在 katex 就绪后加载
let katexModule = null
let katexLoading = null
/** 加载 KaTeX 与化学公式扩展，并通知响应式渲染缓存失效。 */
function loadKatex() {
  if (katexModule) return katexModule
  if (!katexLoading) {
    katexLoading = import('katex').then(async m => {
      katexModule = m.default || m
      try {
        await import('katex/dist/contrib/mhchem.mjs')
      } catch (e) {
        console.warn('[useMarkdown] mhchem load failed:', e)
      }
      markRenderDependencyReady() // 触发已渲染消息重渲染，补齐公式渲染
      return katexModule
    }).catch(e => {
      console.warn('[useMarkdown] katex load failed:', e)
      katexLoading = null
      return null
    })
  }
  return katexLoading
}

// 快速判定文本是否可能含公式定界符（避免对普通消息触发 katex 懒加载）
function mayContainMath(text) {
  return text.indexOf('$') !== -1 || text.indexOf('\\(') !== -1
      || text.indexOf('\\[') !== -1 || text.indexOf('\\ce{') !== -1 || text.indexOf('\\pu{') !== -1
}


/** 内容可能含公式时按需启动 KaTeX 加载。 */
export function ensureMathDependency(text) {
  if (mayContainMath(text)) loadKatex()
}

// ===== 数学公式（KaTeX）渲染 =====
// 将 LaTeX 公式（$...$ / $$...$$ / \(...\) / \[...\]）与化学式（\ce{...}）渲染为 HTML。
// 采用“占位符 + 后置回填”策略：在 marked 解析前抽取公式并渲染为 KaTeX HTML，
// 用占位符替换原文避免被 marked/mermaid 处理；DOMPurify 清洗后再回填 KaTeX HTML
// （KaTeX 以 throwOnError:false + trust:false 运行，输出为纯数学标记不含脚本，回填安全）。
// katex 为懒加载模块，未加载完成时返回 null（回退原始文本显示）
function renderMathToHtml(tex, displayMode) {
  const src = (tex || '').trim()
  if (!src || !katexModule) return null
  try {
    return katexModule.renderToString(src, {
      displayMode,
      throwOnError: false,   // 语法错误不抛异常，改为红色错误提示，避免打断整条消息渲染
      strict: false,
      trust: false,          // 禁用 \href 等命令，杜绝注入
      output: 'htmlAndMathml'
    })
  } catch (e) {
    return null
  }
}

// 抽取一段公式并渲染，返回占位符；渲染失败则回退原始带定界符文本
function pushMath(store, tex, displayMode, rawFallback) {
  const rendered = renderMathToHtml(tex, displayMode)
  if (rendered == null) return rawFallback
  const idx = store.length
  store.push(rendered)
  return '%%KMATH' + idx + '%%'
}

// 在 $...$ 起始处寻找有效的行内公式结束 $（借鉴 KaTeX auto-render 规则，尽量规避货币金额误判）
function findInlineDollarEnd(text, openIdx) {
  const next = text[openIdx + 1]
  if (next === undefined || next === '$' || /\s/.test(next)) return -1
  let i = openIdx + 1
  while (i < text.length) {
    const c = text[i]
    if (c === '\\') { i += 2; continue }              // 跳过转义序列（如 \$、\}）
    if (c === '\n' && text[i + 1] === '\n') return -1  // 行内公式不跨空行
    if (c === '$') {
      const prev = text[i - 1]
      const after = text[i + 1]
      // 结束 $ 前不能是空白，后不能紧跟数字（规避 "$5 ... $10" 这类金额）
      if (prev !== ' ' && prev !== '\t' && prev !== '\n' && !(after && /\d/.test(after))) return i
      return -1
    }
    i++
  }
  return -1
}

// 抽取文本中的数学公式为占位符，跳过代码块/行内代码区域（避免误伤代码里的 $ 与反引号）
export function extractMathBlocks(text, store) {
  let result = ''
  let i = 0
  const n = text.length
  while (i < n) {
    const ch = text[i]
    // 代码围栏 ``` ：整段跳过不处理
    if (ch === '`') {
      if (text.startsWith('```', i)) {
        const end = text.indexOf('```', i + 3)
        if (end === -1) { result += text.slice(i); break }
        result += text.slice(i, end + 3); i = end + 3; continue
      }
      // 行内代码 `...` / ``...`` ：按相同数量反引号配对跳过
      let run = 0
      while (text[i + run] === '`') run++
      const fence = '`'.repeat(run)
      const end = text.indexOf(fence, i + run)
      if (end === -1) { result += text.slice(i, i + run); i += run; continue }
      result += text.slice(i, end + run); i = end + run; continue
    }
    // 块级公式 $$...$$
    if (text.startsWith('$$', i)) {
      const end = text.indexOf('$$', i + 2)
      if (end !== -1) {
        result += pushMath(store, text.slice(i + 2, end), true, text.slice(i, end + 2))
        i = end + 2; continue
      }
    }
    // 块级公式 \[...\]
    if (ch === '\\' && text[i + 1] === '[') {
      const end = text.indexOf('\\]', i + 2)
      if (end !== -1) {
        result += pushMath(store, text.slice(i + 2, end), true, text.slice(i, end + 2))
        i = end + 2; continue
      }
    }
    // 行内公式 \(...\)
    if (ch === '\\' && text[i + 1] === '(') {
      const end = text.indexOf('\\)', i + 2)
      if (end !== -1) {
        result += pushMath(store, text.slice(i + 2, end), false, text.slice(i, end + 2))
        i = end + 2; continue
      }
    }
    // 行内公式 $...$
    if (ch === '$') {
      const end = findInlineDollarEnd(text, i)
      if (end !== -1) {
        result += pushMath(store, text.slice(i + 1, end), false, text.slice(i, end + 1))
        i = end + 1; continue
      }
    }
    result += ch
    i++
  }
  return result
}
