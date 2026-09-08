import { afterEach, describe, expect, it, vi } from 'vitest'
import { attachInlinePanZoom } from '@/composables/markdown/mermaidPanZoom'
import { handleMermaidToolbarClick } from '@/composables/markdown/mermaidRenderer'

afterEach(() => {
  document.body.innerHTML = ''
  vi.restoreAllMocks()
  vi.useRealTimers()
})

/** 创建可复用的已渲染 Mermaid 卡片。 */
function diagram() {
  const card = document.createElement('div')
  card.className = 'mermaid-container'
  card.innerHTML = '<div class="mermaid-toolbar"><button class="mermaid-action" data-act="download"></button></div>' +
    '<div class="mermaid-scroll-wrapper"><pre><svg viewBox="0 0 100 100"></svg></pre></div>'
  document.body.appendChild(card)
  return { card, pre: card.querySelector('pre'), wrapper: card.querySelector('.mermaid-scroll-wrapper') }
}

describe('Mermaid 交互生命周期', () => {
  it('静置图表不注册全局拖拽监听，拖拽中节点卸载即释放监听', async () => {
    const { card, pre, wrapper } = diagram()
    const add = vi.spyOn(document, 'addEventListener')
    const remove = vi.spyOn(document, 'removeEventListener')
    attachInlinePanZoom(pre)
    expect(add.mock.calls.filter(([name]) => name === 'mousemove')).toHaveLength(0)
    wrapper.dispatchEvent(new MouseEvent('mousedown', { button: 0, bubbles: true }))
    const moveHandler = add.mock.calls.find(([name]) => name === 'mousemove')[1]
    card.remove()
    await Promise.resolve()
    expect(remove).toHaveBeenCalledWith('mousemove', moveHandler)
    expect(wrapper.classList.contains('dragging')).toBe(false)
  })

  it('主题重渲染更换 SVG 后缩放操作绑定新 SVG', () => {
    const { pre, wrapper } = diagram()
    attachInlinePanZoom(pre)
    const oldSvg = pre.querySelector('svg')
    pre.innerHTML = '<svg viewBox="0 0 200 200"></svg>'
    pre.querySelector('svg').getScreenCTM = () => null
    attachInlinePanZoom(pre)
    wrapper.dispatchEvent(new WheelEvent('wheel', { deltaY: -100, cancelable: true }))
    expect(oldSvg.getAttribute('viewBox')).toBe('0 0 100 100')
    expect(pre.querySelector('svg').getAttribute('viewBox')).not.toBe('0 0 200 200')
  })

  it('打开菜单后节点卸载会自动注销外部点击监听', async () => {
    vi.useFakeTimers()
    const { card } = diagram()
    const add = vi.spyOn(document, 'addEventListener')
    const remove = vi.spyOn(document, 'removeEventListener')
    handleMermaidToolbarClick({ target: card.querySelector('button') })
    await vi.runAllTimersAsync()
    const handler = add.mock.calls.find(([name]) => name === 'click')[1]
    card.remove()
    await Promise.resolve()
    expect(remove).toHaveBeenCalledWith('click', handler)
  })
})
