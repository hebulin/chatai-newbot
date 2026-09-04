import { expect, test } from '@playwright/test'

/**
 * 安装可变的 API 替身：模拟登录、模型、SSE 与会话服务端持久化，
 * 让浏览器测试不依赖真实模型密钥，也不会修改开发数据库。
 */
async function installApiMock(page) {
  const state = {
    chats: {},
    chatMeta: {},
    lastChatId: null,
    version: 0,
    saveCount: 0
  }
  await page.route('**/api/**', async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname
    const method = request.method()
    // Vite 源码模块也可能包含 /src/api/ 路径，必须放行，只有真实后端前缀才做替身。
    if (!path.startsWith('/api/')) return route.continue()

    if (path === '/api/auth/register-config') {
      return json(route, { success: true, data: { enabled: false, inviteRequired: false, captchaEnabled: false } })
    }
    if (path === '/api/auth/login' && method === 'POST') {
      return json(route, { success: true, username: 'admin', role: 'admin' })
    }
    if (path === '/api/models') {
      return json(route, {
        success: true,
        data: [{
          id: 'model-e2e', modelId: 'model-e2e', displayName: 'E2E Model',
          providerId: 'e2e', providerName: 'E2E Provider', supportsThinking: false,
          supportsMultimodal: false, contextWindow: 32000
        }],
        defaultModelId: 'model-e2e', webSearchEnabled: false, botAvatarSvg: ''
      })
    }
    if (path === '/api/chat/history/summary') {
      return json(route, historySummary(state))
    }
    if (path === '/api/chat/history/single') {
      const chatId = url.searchParams.get('chatId')
      return json(route, {
        success: true,
        exists: Array.isArray(state.chats[chatId]),
        messages: state.chats[chatId] || [],
        meta: state.chatMeta[chatId] || {},
        version: state.version
      })
    }
    if (path === '/api/chat/history' && method === 'POST') {
      const payload = request.postDataJSON()
      Object.assign(state.chats, payload.chats || {})
      Object.assign(state.chatMeta, payload.chatMeta || {})
      state.lastChatId = payload.lastChatId || state.lastChatId
      state.version += 1
      state.saveCount += 1
      const versions = Object.fromEntries(Object.keys(payload.chats || {}).map(id => [id, state.version]))
      return json(route, {
        success: true, version: state.version, versions,
        foldersVersion: payload.baseFoldersVersion || 0, conflicts: []
      })
    }
    if (path === '/api/chat' && method === 'POST') {
      const body = [
        'data: {"choices":[{"delta":{"content":"E2E stream reply"}}]}',
        'data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":3}}',
        'data: [DONE]',
        ''
      ].join('\n\n')
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body })
    }
    if (path === '/api/chat/generate-title' && method === 'POST') {
      return json(route, { success: true, title: 'E2E conversation' })
    }
    if (path === '/api/announcement') {
      return json(route, { success: true, id: '', title: '', content: '', updatedAt: '' })
    }
    if (path === '/api/user/prompt-presets') {
      return json(route, { success: true, presets: [], builtinAgents: [] })
    }
    if (path === '/api/user/profile') {
      return json(route, { success: true, username: 'admin', avatarType: 'default', avatarValue: '' })
    }
    if (path === '/api/admin/observability/history') {
      return json(route, {
        success: true,
        hours: 24,
        data: [{
          bucketStart: '2026-09-04T08:00:00Z', requestCount: 42, serverErrorCount: 3,
          slowRequestCount: 2, averageLatencyMs: 180, chatRequestCount: 9,
          chatSuccessCount: 8, chatFailureCount: 1, chatCancelledCount: 0,
          chatTimeoutCount: 0, chatRejectedCount: 1, chatAvgTimeToFirstTokenMs: 260
        }]
      })
    }
    if (path === '/api/admin/observability') {
      return json(route, {
        success: true,
        healthy: true,
        data: {
          metricsSince: '2026-09-01T00:00:00Z', uptimeSeconds: 3600,
          requestCount: 42, serverErrorCount: 3, slowRequestCount: 2,
          averageLatencyMs: 180, activeChats: 1, chatRequestCount: 9,
          chatSuccessCount: 8, chatFailureCount: 1, chatRejectedCount: 1,
          chatAvgTimeToFirstTokenMs: 260, heapUsedBytes: 1048576, heapMaxBytes: 8388608
        }
      })
    }
    if (path === '/api/admin/settings/storage') {
      return json(route, { success: true, data: { useSqlite: true, dbFileSize: '1 MB' } })
    }
    if (path === '/api/admin/settings/quota') {
      return json(route, { success: true, data: {} })
    }
    if (path === '/api/admin/settings/security') {
      return json(route, { success: true, data: {} })
    }
    if (path === '/api/admin/settings/billing') {
      return json(route, {
        success: true,
        data: { displayMode: 'token', defaultCurrency: 'CNY', currencies: [{ code: 'CNY', name: '人民币', symbol: '¥', rate: 1 }] }
      })
    }
    if (path === '/api/admin/settings/health-check') {
      return json(route, { success: true, data: { enabled: false, intervalMinutes: 360 } })
    }
    if (path === '/api/admin/models') {
      return json(route, { success: true, data: [] })
    }
    return json(route, { success: true, data: [] })
  })
  return state
}

/** 使用 JSON 内容类型完成被拦截的 API 请求。 */
function json(route, body) {
  return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
}

/** 从当前模拟服务端状态构造真实摘要接口兼容的响应。 */
function historySummary(state) {
  const summaries = Object.entries(state.chats).map(([id, messages]) => {
    const firstUser = messages.find(message => message.role === 'user')
    const last = messages[messages.length - 1]
    return {
      id,
      title: state.chatMeta[id]?.title || firstUser?.content || 'New chat',
      preview: firstUser?.content || '',
      lastTime: last?.time || '10:00',
      count: messages.length,
      version: state.version
    }
  })
  return {
    success: true,
    summaries,
    chatMeta: state.chatMeta,
    lastChatId: state.lastChatId,
    deletedChatIds: [],
    folders: [],
    foldersVersion: 0,
    version: state.version
  }
}

test('管理员登录、SSE 对话并在刷新后恢复服务端会话', async ({ page }) => {
  const state = await installApiMock(page)
  await page.goto('/login')

  await page.locator('.brand-cta').click()
  await page.locator('#login-username').fill('admin')
  await page.locator('#login-password').fill('admin123')
  await page.locator('form.form-pane-active .submit-cta').click()

  await expect(page).toHaveURL(/\/$/)
  await expect(page.locator('.input-area textarea')).toBeVisible()
  await page.locator('.input-area textarea').fill('端到端浏览器测试')
  await page.locator('.send-btn').click()
  await expect(page.getByText('E2E stream reply')).toBeVisible()
  await expect.poll(() => state.saveCount, { timeout: 10000 }).toBeGreaterThan(0)

  await page.reload()
  await expect(page.getByText('端到端浏览器测试')).toBeVisible()
  await expect(page.getByText('E2E stream reply')).toBeVisible()
})

test('管理员可查看跨重启累计指标与 24 小时趋势', async ({ page }) => {
  await installApiMock(page)
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/admin/settings')

  await expect(page.getByRole('heading', { name: '系统设置' })).toBeVisible()
  await expect(page.getByText('最近 24 小时趋势')).toBeVisible()
  await expect(page.locator('.observability-history-table')).toContainText('42')
  await expect(page.locator('.observability-history-table')).toContainText('260')
})
