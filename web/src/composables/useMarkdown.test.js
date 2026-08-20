// useMarkdown 单元测试：HTML 转义、Markdown 渲染与 XSS 清洗
import { describe, it, expect } from 'vitest'
import { escapeHtml, renderMarkdown } from '@/composables/useMarkdown'

describe('escapeHtml', () => {
  it('转义尖括号与引号，防止 HTML 注入', () => {
    const out = escapeHtml('<div class="a">&\'"</div>')
    expect(out).not.toContain('<div')
    expect(out).toContain('&lt;div')
    expect(out).toContain('&amp;')
  })

  it('普通文本原样返回', () => {
    expect(escapeHtml('hello 你好')).toBe('hello 你好')
  })
})

describe('renderMarkdown', () => {
  it('渲染基础 Markdown 语法', () => {
    const html = renderMarkdown('**加粗** 普通文本')
    expect(html).toContain('<strong>加粗</strong>')
  })

  it('剥离 script 标签与事件处理器（XSS 防护）', () => {
    const html = renderMarkdown('text <script>alert(1)</script> <img src=x onerror=alert(1)>')
    expect(html).not.toContain('<script')
    expect(html).not.toContain('onerror')
  })

  it('代码块携带语言标记，供后续高亮与头部工具栏识别', () => {
    const html = renderMarkdown('```html\n<div>hi</div>\n```')
    expect(html).toContain('language-html')
  })

  it('链接统一新标签页打开并带 rel 防护', () => {
    const html = renderMarkdown('[link](https://example.com)')
    expect(html).toContain('target="_blank"')
    expect(html).toContain('rel="noopener noreferrer"')
  })

  it('空文本返回空串', () => {
    expect(renderMarkdown('')).toBe('')
    expect(renderMarkdown(null)).toBe('')
  })
})
