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
    saveCount: 0,
    chatRequests: [],
    reply: 'E2E stream reply'
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
      state.chatRequests.push(request.postDataJSON())
      const body = [
        `data: ${JSON.stringify({ choices: [{ delta: { content: state.reply } }] })}`,
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

test('编辑用户消息在原会话生成回答分页版本并在刷新后保留', async ({ page }) => {
  const state = await installApiMock(page)
  state.lastChatId = 'edit-resend-chat'
  state.chatMeta[state.lastChatId] = { title: '编辑重发测试' }
  state.chats[state.lastChatId] = [
    { id: 'user-original', role: 'user', content: '编辑前的问题' },
    { id: 'reply-original', role: 'assistant', content: '编辑前的回答' }
  ]
  state.reply = '编辑后生成的新回答'
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/')
  await expect(page.getByText('编辑前的回答', { exact: true })).toBeVisible()
  await page.getByTitle('编辑重发', { exact: true }).click()
  await page.locator('.el-message-box textarea').fill('编辑后的问题')
  await page.locator('.el-message-box').getByRole('button', { name: '重新发送', exact: true }).click()
  await expect(page.getByText('编辑后生成的新回答', { exact: true })).toBeVisible()
  await expect(page.locator('.version-indicator')).toHaveText('2/2')
  await expect(page.locator('.msg-wrapper')).toHaveCount(2)
  await expect.poll(() => state.chats['edit-resend-chat'][1].versions?.length).toBe(2)
  expect(Object.keys(state.chats)).toEqual(['edit-resend-chat'])
  expect(state.lastChatId).toBe('edit-resend-chat')
  expect(state.chatRequests[0]).toMatchObject({
    chatId: 'edit-resend-chat', messages: [{ role: 'user', content: '编辑后的问题' }]
  })
  expect(state.chats['edit-resend-chat'][0].id).toBe('user-original')
  await page.getByRole('button', { name: '上一版本', exact: true }).click()
  await expect(page.getByText('编辑前的回答', { exact: true })).toBeVisible()
  await expect(page.locator('.version-indicator')).toHaveText('1/2')
  await page.getByRole('button', { name: '下一版本', exact: true }).click()
  await expect(page.getByText('编辑后生成的新回答', { exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByText('编辑后的问题', { exact: true })).toBeVisible()
  await expect(page.getByText('编辑后生成的新回答', { exact: true })).toBeVisible()
  await expect(page.locator('.version-indicator')).toHaveText('2/2')
  expect(Object.keys(state.chats)).toEqual(['edit-resend-chat'])
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

test('生产包按图表类型加载布局引擎，Excel 仅在导出时加载', async ({ page }) => {
  test.setTimeout(60000)
  const state = await installApiMock(page)
  const loaded = new Set()
  const pageErrors = []
  page.on('request', request => loaded.add(new URL(request.url()).pathname))
  page.on('pageerror', error => pageErrors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/')
  await expect(page.locator('.input-area textarea')).toBeVisible()
  expect([...loaded].some(path => /mermaid|xlsx/.test(path))).toBe(false)

  state.reply = '```mermaid\nflowchart TD\nA[开始] --> B[完成]\n```'
  await page.locator('.input-area textarea').fill('普通流程图')
  await page.locator('.send-btn').click()
  await expect(page.locator('.mermaid-view svg')).toHaveCount(1)
  expect([...loaded].some(path => /mermaid\.core/.test(path))).toBe(true)
  expect([...loaded].some(path => /mermaid-elk|mermaid-mindmap|xlsx-export/.test(path))).toBe(false)

  state.reply = '```mermaid\nflowchart-elk TD\nA[开始] --> B[完成]\n```'
  await page.locator('.input-area textarea').fill('ELK 流程图')
  await page.locator('.send-btn').click()
  await expect(page.locator('.mermaid-view svg')).toHaveCount(2)
  expect([...loaded].some(path => /mermaid-elk/.test(path))).toBe(true)
  expect([...loaded].some(path => /mermaid-mindmap|xlsx-export/.test(path))).toBe(false)

  state.reply = '```mermaid\nmindmap\n  root((主题))\n    分支一\n    分支二\n```\n\n| 名称 | 数量 |\n| --- | --- |\n| 测试 | 2 |'
  await page.locator('.input-area textarea').fill('思维导图和表格')
  await page.locator('.send-btn').click()
  await expect(page.locator('.mermaid-view svg')).toHaveCount(3)
  expect([...loaded].some(path => /mermaid-mindmap/.test(path))).toBe(true)
  expect([...loaded].some(path => /xlsx-export/.test(path))).toBe(false)
  await page.locator('.md-table-btn').click()
  expect([...loaded].some(path => /xlsx-export/.test(path))).toBe(false)
  const download = page.waitForEvent('download')
  await page.locator('.md-table-menu-item[data-fmt="xlsx"]').click()
  expect((await download).suggestedFilename()).toMatch(/\.xlsx$/)
  expect([...loaded].some(path => /xlsx-export/.test(path))).toBe(true)
  expect(pageErrors).toEqual([])
})
