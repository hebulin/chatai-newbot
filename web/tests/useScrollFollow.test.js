import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, shallowRef } from 'vue'

import { useScrollFollow } from '@/composables/useScrollFollow'

let mountedApp = null

// 为 jsdom 元素模拟浏览器会自动夹紧的滚动几何数据
function mockScrollGeometry(element, initial = {}) {
  const geometry = {
    scrollHeight: initial.scrollHeight ?? 1000,
    clientHeight: initial.clientHeight ?? 400,
    scrollTop: initial.scrollTop ?? 0
  }
  Object.defineProperties(element, {
    scrollHeight: { configurable: true, get: () => geometry.scrollHeight },
    clientHeight: { configurable: true, get: () => geometry.clientHeight },
    scrollTop: {
      configurable: true,
      get: () => geometry.scrollTop,
      set: value => {
        const max = Math.max(0, geometry.scrollHeight - geometry.clientHeight)
        geometry.scrollTop = Math.max(0, Math.min(Number(value) || 0, max))
      }
    }
  })
  return geometry
}

// 在组件 setup 内创建滚动控制器，使卸载清理钩子按真实生命周期执行
function mountScrollFollower(elementRef, options) {
  let controller = null
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp({
    setup() {
      controller = useScrollFollow(elementRef, options)
      return () => h('div')
    }
  })
  app.mount(host)
  mountedApp = { app, host }
  return controller
}

// 等待 MutationObserver 与滚动 rAF 队列全部完成
async function flushScrollUpdates(ms = 32) {
  await nextTick()
  await Promise.resolve()
  await vi.advanceTimersByTimeAsync(ms)
  await nextTick()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.stubGlobal('requestAnimationFrame', callback => setTimeout(() => callback(Date.now()), 16))
  vi.stubGlobal('cancelAnimationFrame', id => clearTimeout(id))
})

afterEach(() => {
  if (mountedApp) {
    mountedApp.app.unmount()
    mountedApp.host.remove()
    mountedApp = null
  }
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('useScrollFollow 共用滚动控制器', () => {
  it('离底暂停并在回到底部附近后恢复 MutationObserver 跟随', async () => {
    const element = document.createElement('div')
    const geometry = mockScrollGeometry(element)
    const elementRef = shallowRef(element)
    const follower = mountScrollFollower(elementRef)
    follower.init()
    follower.scrollToBottomImmediate()
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(600)

    geometry.scrollTop = 200
    element.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    element.dispatchEvent(new Event('scroll'))
    geometry.scrollHeight = 1100
    element.appendChild(document.createElement('span'))
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(200)

    geometry.scrollTop = 690
    element.dispatchEvent(new Event('scroll'))
    geometry.scrollHeight = 1200
    element.appendChild(document.createElement('span'))
    await flushScrollUpdates()
    expect(geometry.scrollTop).toBe(800)
  })

  it('动态容器替换时解绑旧节点并只跟随新节点', async () => {
    const first = document.createElement('div')
    mockScrollGeometry(first)
    const elementRef = shallowRef(first)
    const follower = mountScrollFollower(elementRef, { nearBottomThreshold: 30 })
    follower.init()

    const second = document.createElement('div')
    const secondGeometry = mockScrollGeometry(second, { scrollHeight: 700, clientHeight: 300 })
    elementRef.value = second
    follower.init()
    follower.scrollToBottomImmediate()
    await flushScrollUpdates()
    expect(secondGeometry.scrollTop).toBe(400)

    first.dispatchEvent(new WheelEvent('wheel', { deltaY: -120 }))
    secondGeometry.scrollHeight = 800
    second.appendChild(document.createElement('span'))
    await flushScrollUpdates()
    expect(secondGeometry.scrollTop).toBe(500)
  })
})
