import { afterEach, describe, expect, it, vi } from 'vitest'
import { enhanceStickyHeaders } from '@/composables/markdown/stickyHeaders'

afterEach(async () => {
  document.body.innerHTML = ''
  await Promise.resolve()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

/** 安装只记录资源生命周期的观察器替身；真实吸顶几何由浏览器回归覆盖。 */
function mockObservers() {
  const instances = []
  const Observer = vi.fn(function () {
    this.observe = vi.fn()
    this.unobserve = vi.fn()
    this.disconnect = vi.fn()
    instances.push(this)
  })
  vi.stubGlobal('ResizeObserver', Observer)
  vi.stubGlobal('IntersectionObserver', Observer)
  vi.stubGlobal('requestAnimationFrame', vi.fn(() => 123))
  vi.stubGlobal('cancelAnimationFrame', vi.fn())
  return instances
}

describe('富文本吸顶资源生命周期', () => {
  it.each(['cards', 'viewport'])('纵向滚动无需 JS 跟随，移除 %s 后释放共享观察器与待执行帧', async target => {
    const observers = mockObservers()
    const root = document.createElement('div')
    root.style.overflowY = 'auto'
    root.innerHTML = '<pre class="md-code-block"></pre><div class="mermaid-container"></div>'
      + '<div class="md-table-container"><div class="md-table-sticky-group"></div>'
      + '<div class="table-wrapper"><table><thead><tr><th>名称</th></tr></thead>'
      + '<tbody><tr><td>数据</td></tr></tbody></table></div></div>'
    document.body.appendChild(root)
    const add = vi.spyOn(root, 'addEventListener')
    const wrapper = root.querySelector('.table-wrapper')
    const addHorizontal = vi.spyOn(wrapper, 'addEventListener')
    const removeHorizontal = vi.spyOn(wrapper, 'removeEventListener')
    enhanceStickyHeaders(root)
    enhanceStickyHeaders(root)
    expect(add.mock.calls.filter(([type]) => type === 'scroll')).toHaveLength(0)
    expect(observers).toHaveLength(1)
    expect(addHorizontal.mock.calls.filter(([type]) => type === 'scroll')).toHaveLength(1)
    if (target === 'cards') root.replaceChildren()
    else root.remove()
    await Promise.resolve()
    expect(removeHorizontal).toHaveBeenCalledWith('scroll', expect.any(Function))
    observers.forEach(observer => expect(observer.disconnect).toHaveBeenCalledOnce())
    expect(cancelAnimationFrame).toHaveBeenCalledWith(123)
  })
})
