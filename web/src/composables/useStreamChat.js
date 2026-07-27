import { ref } from 'vue'
import { useRouter } from 'vue-router'

export function useStreamChat() {
  const router = useRouter()
  const isStreaming = ref(false)
  const thinkingContent = ref('')
  const answerContent = ref('')
  const thinkingTime = ref(null)
  const usage = ref(null)
  const error = ref(null)

  let controller = null
  let buffer = ''
  let thinkingStartTime = null

  function reset() {
    buffer = ''
    thinkingContent.value = ''
    answerContent.value = ''
    thinkingTime.value = null
    usage.value = null
    error.value = null
    thinkingStartTime = null
  }

  async function send(requestBody, { onUpdate, onDone, onError } = {}) {
    reset()
    isStreaming.value = true
    controller = new AbortController()

    const token = localStorage.getItem('token')
    let hasResponse = false

    try {
      const resp = await fetch('/api/chat', {
        method: 'POST',
        headers: {
          'Authorization': 'Bearer ' + token,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(requestBody),
        signal: controller.signal
      })

      // 401 处理
      if (resp.status === 401) {
        isStreaming.value = false
        localStorage.removeItem('token')
        localStorage.removeItem('username')
        localStorage.removeItem('role')
        router.push('/login')
        return
      }

      if (!resp.ok) {
        const text = await resp.text()
        let errMsg = '服务器错误 (' + resp.status + ')'
        try {
          const json = JSON.parse(text)
          if (json.message) errMsg = json.message
          else if (json.error && json.error.message) errMsg = json.error.message
        } catch (e) { /* ignore */ }
        throw new Error(errMsg)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()

      const read = async () => {
        const result = await reader.read()
        if (result.done) {
          // 处理buffer中残留数据
          if (buffer.trim()) {
            const remainingLine = buffer.trim()
            if (remainingLine.startsWith('{')) {
              try {
                const json = JSON.parse(remainingLine)
                if (json.error) {
                  answerContent.value += '\n\n**错误:** ' + (json.error.message || '未知错误')
                }
              } catch (e) { /* skip */ }
            }
          }
          isStreaming.value = false
          if (onDone) onDone({
            content: answerContent.value,
            reasoning_content: thinkingContent.value,
            thinkingTime: thinkingTime.value,
            usage: usage.value
          })
          return
        }

        const chunk = decoder.decode(result.value, { stream: true })
        buffer += chunk
        const lines = buffer.split('data:')
        buffer = lines.pop() || ''
        let updated = false

        for (const line of lines) {
          const trimmed = line.trim()
          if (!trimmed || trimmed === '[DONE]') continue
          if (trimmed.startsWith('{')) {
            try {
              const json = JSON.parse(trimmed)
              if (json.error) {
                answerContent.value += '\n\n**错误:** ' + (json.error.message || '未知错误')
                updated = true
                continue
              }
              // 提取usage数据
              if (json.usage) {
                usage.value = json.usage
              }
              if (json.choices && json.choices[0] && json.choices[0].delta) {
                const delta = json.choices[0].delta
                if (!hasResponse) hasResponse = true

                // 兼容不同厂商的思考内容字段
                const deltaThinking = delta.reasoning_content || delta.reasoning
                if (deltaThinking) {
                  if (!thinkingStartTime) thinkingStartTime = Date.now()
                  thinkingContent.value += deltaThinking
                  updated = true
                }
                if (delta.content) {
                  if (thinkingStartTime && !thinkingTime.value) {
                    thinkingTime.value = Math.round((Date.now() - thinkingStartTime) / 1000)
                  }
                  answerContent.value += delta.content
                  updated = true
                }
              }
            } catch (e) { /* skip parse error */ }
          }
        }

        if (updated && onUpdate) {
          onUpdate({
            content: answerContent.value,
            reasoning_content: thinkingContent.value,
            thinkingTime: thinkingTime.value,
            usage: usage.value
          })
        }

        return read()
      }

      await read()
    } catch (err) {
      isStreaming.value = false
      if (err.name === 'AbortError') {
        // 用户中断
        if (onDone) onDone({
          content: answerContent.value,
          reasoning_content: thinkingContent.value,
          thinkingTime: thinkingTime.value,
          usage: usage.value,
          interrupted: true
        })
        return
      }
      error.value = err.message
      if (onError) onError(err)
    }
  }

  function stop() {
    if (controller) {
      controller.abort()
      controller = null
    }
  }

  return {
    isStreaming, thinkingContent, answerContent, thinkingTime, usage, error,
    send, stop, reset
  }
}
