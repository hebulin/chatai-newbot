import { ref } from 'vue'

// ===== 全局 TTS 朗读状态（模块级单例）=====
// 保证全局有且仅有一个正在播放的内容：ChatMessages 消息工具栏喇叭按钮与
// ChatView 悬浮播放窗共享同一份状态，任一入口操作都反映到所有 UI。
const supported = typeof window !== 'undefined' && 'speechSynthesis' in window

// 当前播放/暂停中的消息：{ absIdx（完整消息列表绝对下标）, text（朗读纯文本） }，空闲时为 null
const current = ref(null)
// 播放状态：'idle' | 'playing' | 'paused'
const status = ref('idle')

// 播放序号令牌：speechSynthesis.cancel() 会异步触发旧 utterance 的 onend/onerror，
// 若回调直接清空状态，会把刚启动的新播放状态误清（曾导致点击其他消息喇叭不变停止态、
// 无法停止只能等播完）。每次启动/停止递增令牌，回调仅在自己仍是当前令牌时才生效
let speakSeq = 0

// Markdown 文本转朗读纯文本：去代码块/图片/链接语法/标题与强调符号，避免读出符号噪音
function stripMarkdownForSpeech(md) {
  return String(md || '')
    .replace(/```[\s\S]*?(```|$)/g, ' ')          // 代码块
    .replace(/!\[[^\]]*\]\([^)]*\)/g, ' ')        // 图片
    .replace(/\[([^\]]*)\]\([^)]*\)/g, '$1')      // 链接保留文字
    .replace(/<[^>]+>/g, ' ')                     // HTML 标签
    .replace(/[#>*`_~|-]/g, ' ')                  // 标题/引用/强调/列表符号
    .replace(/\s+/g, ' ')
    .trim()
}

// 判断指定绝对下标的消息是否为当前播放/暂停中的消息
function isCurrent(absIdx) {
  return current.value !== null && current.value.absIdx === absIdx
}

// 开始朗读指定消息：自动停止上一段（cancel 触发的旧回调因令牌过期被忽略）
function start(absIdx, content) {
  if (!supported) return
  const text = stripMarkdownForSpeech(content)
  if (!text) return
  const mySeq = ++speakSeq
  window.speechSynthesis.cancel()
  const utter = new SpeechSynthesisUtterance(text)
  utter.lang = /[一-鿿]/.test(text) ? 'zh-CN' : 'en-US'
  utter.onend = () => {
    if (mySeq !== speakSeq) return
    current.value = null
    status.value = 'idle'
  }
  utter.onerror = () => {
    if (mySeq !== speakSeq) return
    current.value = null
    status.value = 'idle'
  }
  current.value = { absIdx, text }
  status.value = 'playing'
  window.speechSynthesis.speak(utter)
}

// 消息工具栏喇叭点击逻辑：当前消息播放中→停止；当前消息已暂停→继续；其他消息→切换播放
function toggle(absIdx, content) {
  if (!supported) return
  if (isCurrent(absIdx)) {
    if (status.value === 'paused') resume()
    else stop()
    return
  }
  start(absIdx, content)
}

// 暂停播放（浮窗"暂停"按钮）
function pause() {
  if (!supported || status.value !== 'playing') return
  window.speechSynthesis.pause()
  status.value = 'paused'
}

// 继续播放（浮窗"继续"按钮 / 暂停中再点喇叭）
function resume() {
  if (!supported || status.value !== 'paused') return
  window.speechSynthesis.resume()
  status.value = 'playing'
}

// 停止并清空播放状态（浮窗关闭、切换会话、页面卸载时调用；调用后浮窗自动隐藏）
function stop() {
  speakSeq++ // 使未决的旧 utterance 回调全部失效
  if (supported) window.speechSynthesis.cancel()
  current.value = null
  status.value = 'idle'
}

export function useSpeech() {
  return {
    speechSupported: supported,
    current,
    status,
    isCurrent,
    start,
    toggle,
    pause,
    resume,
    stop
  }
}
