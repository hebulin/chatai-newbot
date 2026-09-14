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
    reply: 'E2E stream reply',
    finishReason: 'stop',
    sendDone: true,
    completionTokens: 3,
    globalMaxOutputTokens: 16384,
    globalContextWindow: 32000,
    adminModels: [],
    announcement: { success: true, id: '', title: '', content: '', updatedAt: '' }
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
    if (path === '/api/auth/me') {
      return json(route, { success: true, id: 'admin', username: 'admin', role: 'admin' })
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
          supportsMultimodal: false
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
        `data: ${JSON.stringify({ choices: [{ delta: {}, finish_reason: state.finishReason }], usage: { prompt_tokens: 4, completion_tokens: state.completionTokens } })}`,
        state.sendDone ? 'data: [DONE]' : '',
        ''
      ].join('\n\n')
      return route.fulfill({ status: 200, contentType: 'text/event-stream', body })
    }
    if (path === '/api/chat/generate-title' && method === 'POST') {
      return json(route, { success: true, title: 'E2E conversation' })
    }
    if (path === '/api/announcement') {
      return json(route, state.announcement)
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
    if (path === '/api/admin/settings/chat-context') {
      if (method === 'PUT') {
        state.globalContextWindow = request.postDataJSON().contextWindow
        return json(route, { success: true })
      }
      return json(route, { success: true, data: { globalContextWindow: state.globalContextWindow,
        availableModels: state.adminModels.map(model => ({ ...model, name: model.displayName })),
        models: state.adminModels.filter(model => model.contextWindow != null).map(model => ({ ...model, name: model.displayName })) } })
    }
    if (path.startsWith('/api/admin/settings/chat-context/models/') && method === 'PUT') {
      Object.assign(state.adminModels.find(model => model.id === path.split('/').pop()), request.postDataJSON())
      return json(route, { success: true })
    }
    if (path === '/api/admin/settings/chat-output') {
      if (method === 'PUT') {
        state.globalMaxOutputTokens = request.postDataJSON().maxOutputTokens
        return json(route, { success: true })
      }
      return json(route, { success: true, data: { globalMaxOutputTokens: state.globalMaxOutputTokens, availableModels: state.adminModels.map(model => ({ ...model, name: model.displayName })), models: state.adminModels.filter(model => model.maxOutputTokens != null).map(model => ({ ...model, name: model.displayName })) } })
    }
    if (path.startsWith('/api/admin/settings/chat-output/models/') && method === 'PUT') {
      Object.assign(state.adminModels.find(model => model.id === path.split('/').pop()), request.postDataJSON())
      return json(route, { success: true })
    }
    if (path === '/api/admin/models') {
      return json(route, { success: true, data: state.adminModels })
    }
    if (path.startsWith('/api/admin/models/') && method === 'PUT') {
      const id = path.split('/').pop()
      Object.assign(state.adminModels.find(model => model.id === id), request.postDataJSON())
      return json(route, { success: true })
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

// 在两种界面语言下验证真实弹窗标题及确认、刷新、重新登录和重新发布的已读行为。
for (const { locale, title, fallback, confirm, dismiss } of [
  { locale: 'zh', title: '维护通知', fallback: '系统公告', confirm: '我知道了', dismiss: '以后不再提示' },
  { locale: 'en', title: 'Maintenance notice', fallback: 'Announcement', confirm: 'Got it', dismiss: "Don't remind me again" }
]) {
  test(`系统公告标题无装饰前缀且已读逻辑保持不变（${locale}）`, async ({ page }) => {
    const state = await installApiMock(page)
    state.announcement = { success: true, id: 'announcement-1', title: `  ${title}  `, content: '公告正文', updatedAt: 'version-1' }
    await page.addInitScript(language => {
      localStorage.setItem('username', 'admin')
      localStorage.setItem('role', 'admin')
      localStorage.setItem('locale', language)
    }, locale)
    await page.goto('/')
    const dialog = page.locator('.el-message-box')
    await expect(dialog.locator('.el-message-box__title')).toHaveText(title)
    await expect(dialog).toContainText('公告正文')
    await expect(dialog.getByRole('checkbox', { name: dismiss, exact: true })).not.toBeChecked()
    expect(await page.evaluate(() => sessionStorage.getItem('announcement_shown'))).toBeNull()
    await page.evaluate(() => localStorage.setItem('announcement_read_at', 'legacy-read-marker'))

    // 普通确认仅记录本次登录已提示，并清理旧版标记；刷新仍不重复显示。
    await dialog.getByRole('button', { name: confirm, exact: true }).click()
    await expect(dialog).toHaveCount(0)
    expect(await page.evaluate(() => sessionStorage.getItem('announcement_shown'))).toBe('announcement-1|version-1')
    expect(await page.evaluate(() => localStorage.getItem('announcement_dismissed'))).toBeNull()
    expect(await page.evaluate(() => localStorage.getItem('announcement_read_at'))).toBeNull()
    await reloadAnnouncementPage(page)
    await expect(dialog).toHaveCount(0)

    // 模拟登录时清除会话提示标记，未永久忽略的公告会再次展示。
    await page.evaluate(() => sessionStorage.removeItem('announcement_shown'))
    await reloadAnnouncementPage(page)
    await expect(dialog.locator('.el-message-box__title')).toHaveText(title)
    // Element Plus 隐藏原生 input，通过用户可见的标签切换并核对真实勾选状态。
    await dialog.getByText(dismiss, { exact: true }).click()
    await expect(dialog.getByRole('checkbox', { name: dismiss, exact: true })).toBeChecked()
    await dialog.getByRole('button', { name: confirm, exact: true }).click()
    await expect(dialog).toHaveCount(0)
    expect(await page.evaluate(() => localStorage.getItem('announcement_dismissed'))).toBe('announcement-1|version-1')
    await page.evaluate(() => sessionStorage.removeItem('announcement_shown'))
    await reloadAnnouncementPage(page)
    await expect(dialog).toHaveCount(0)

    // 同一公告重新发布后旧已读标记失效；空白标题仍使用原中英文默认文案。
    state.announcement.updatedAt = 'version-2'
    state.announcement.title = ' \n '
    await page.evaluate(() => sessionStorage.setItem('announcement_shown', 'announcement-1|version-1'))
    await reloadAnnouncementPage(page)
    await expect(dialog.locator('.el-message-box__title')).toHaveText(fallback)
    await expect(dialog.getByRole('checkbox', { name: dismiss, exact: true })).not.toBeChecked()
    await dialog.getByRole('button', { name: confirm, exact: true }).click()
    await expect(dialog).toHaveCount(0)
    expect(await page.evaluate(() => sessionStorage.getItem('announcement_shown'))).toBe('announcement-1|version-2')
    expect(await page.evaluate(() => localStorage.getItem('announcement_dismissed'))).toBe('announcement-1|version-1')
  })
}

/** 刷新并等待公告响应完成及浏览器绘制，避免在异步检查公告之前断言弹窗不存在。 */
async function reloadAnnouncementPage(page) {
  const response = page.waitForResponse('**/api/announcement')
  await page.reload()
  await (await response).finished()
  await expect(page.locator('.input-area textarea')).toBeVisible()
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
}

/** 验证两种容量独立保存、草稿隔离、无障碍焦点以及上下文覆盖清空。 */
test('上下文大小与输出大小独立配置并在刷新后保持', async ({ page }) => {
  const state = await installApiMock(page)
  state.adminModels = [{ id: 'context-model', modelId: 'context-model', displayName: '容量测试模型', enabled: true }]
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/admin/settings?section=context')
  const context = page.getByRole('region', { name: '上下文大小', exact: true })
  const menu = page.getByRole('navigation', { name: '设置分类' })
  const globalInput = context.getByRole('spinbutton', { name: '全局上下文大小' })
  await expect(globalInput).toHaveValue('32000')
  await expect(context.getByRole('combobox')).toHaveCount(0)
  await globalInput.fill('0')
  await context.getByRole('button', { name: '保存全局配置' }).click()
  await expect(context.getByRole('alert')).toContainText('请输入 1')
  expect(state.globalContextWindow).toBe(32000)
  await globalInput.fill('128000')
  await context.getByRole('button', { name: '保存全局配置' }).click()
  await expect.poll(() => state.globalContextWindow).toBe(128000)
  expect(state.globalMaxOutputTokens).toBe(16384)
  await context.getByRole('tab', { name: '单个模型配置', exact: true }).click()
  await context.getByRole('button', { name: '新增', exact: true }).click()
  const row = context.locator('[data-output-row="0"]')
  await expect(row.getByRole('combobox')).toBeFocused()
  await row.getByRole('combobox').selectOption('context-model')
  await row.getByRole('spinbutton').fill('200000')
  await menu.getByRole('link', { name: '输出大小', exact: true }).click()
  const output = page.getByRole('region', { name: '输出大小', exact: true })
  await output.getByRole('spinbutton', { name: '全局输出上限' }).fill('65536')
  await output.getByRole('button', { name: '保存全局配置' }).click()
  await expect.poll(() => state.globalMaxOutputTokens).toBe(65536)
  await menu.getByRole('link', { name: '上下文大小', exact: true }).click()
  await expect(row.getByRole('spinbutton')).toHaveValue('200000')
  await row.getByRole('button', { name: '保存', exact: true }).click()
  await expect.poll(() => state.adminModels[0].contextWindow).toBe(200000)
  expect(state.adminModels[0].maxOutputTokens).toBeUndefined()
  await page.reload()
  await expect(globalInput).toHaveValue('128000')
  await context.getByRole('tab', { name: '单个模型配置', exact: true }).click()
  await expect(row.getByRole('spinbutton')).toHaveValue('200000')
  await page.screenshot({ path: test.info().outputPath('context-desktop.png'), fullPage: true })
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await expect.poll(() => state.adminModels[0].contextWindow).toBeNull()
  expect(state.globalMaxOutputTokens).toBe(65536)
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('输出配置分Tab且模型配置从空白新增，保存和删除后刷新保持正确', async ({ page }) => {
  const state = await installApiMock(page)
  state.adminModels = [
    { id: 'model-e2e', modelId: 'model-e2e', displayName: '长输出模型', providerName: '测试厂商', enabled: true, protocol: 'openai' },
    { id: 'model-other', modelId: 'model-other', displayName: '第二模型', providerName: '测试厂商', enabled: true, protocol: 'openai' }
  ]
  const errors = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/admin/settings')
  const card = page.getByRole('region', { name: '输出大小' })
  await expect(card.getByRole('spinbutton', { name: '全局输出上限' })).toHaveValue('16384')
  await card.getByRole('spinbutton', { name: '全局输出上限' }).fill('8192')
  await card.getByRole('button', { name: '保存全局配置' }).click()
  await expect.poll(() => state.globalMaxOutputTokens).toBe(8192)
  await card.getByRole('tab', { name: '单个模型配置', exact: true }).click()
  await expect(card.getByText('暂无单个模型配置', { exact: true })).toBeVisible()
  await expect(card.locator('[data-output-row]')).toHaveCount(0)
  await page.screenshot({ path: test.info().outputPath('output-empty-desktop.png'), fullPage: true })
  await card.getByRole('button', { name: '新增', exact: true }).click()
  const row = card.locator('[data-output-row="0"]')
  await expect(row.getByRole('combobox')).toHaveValue('')
  await expect(row.getByRole('spinbutton')).toHaveValue('')
  await row.getByRole('button', { name: '保存', exact: true }).click()
  await expect(row.getByRole('alert')).toHaveText('请选择模型')
  await row.getByRole('combobox').selectOption('model-e2e')
  await row.getByRole('button', { name: '保存', exact: true }).click()
  await expect(row.getByRole('alert')).toContainText('请输入 0')
  await row.getByRole('spinbutton').fill('65536')
  await row.getByRole('button', { name: '保存', exact: true }).click()
  await expect.poll(() => state.adminModels[0].maxOutputTokens).toBe(65536)
  await page.screenshot({ path: test.info().outputPath('output-configured-desktop.png'), fullPage: true })
  await card.getByRole('button', { name: '新增', exact: true }).click()
  const second = card.locator('[data-output-row="1"]')
  await expect(second.getByRole('combobox').locator('option[value="model-e2e"]')).toHaveAttribute('disabled', '')
  await second.getByRole('button', { name: '取消', exact: true }).click()
  await page.reload()
  await card.getByRole('tab', { name: '单个模型配置', exact: true }).click()
  await expect(card.locator('[data-output-row]')).toHaveCount(1)
  await expect(row.getByRole('spinbutton')).toHaveValue('65536')
  await row.getByRole('spinbutton').fill('0')
  await row.getByRole('button', { name: '保存', exact: true }).click()
  await expect.poll(() => state.adminModels[0].maxOutputTokens).toBe(0)
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await expect.poll(() => state.adminModels[0].maxOutputTokens).toBeNull()
  await expect(card.getByText('暂无单个模型配置', { exact: true })).toBeVisible()
  await page.reload()
  await card.getByRole('tab', { name: '单个模型配置', exact: true }).click()
  await expect(card.locator('[data-output-row]')).toHaveCount(0)
  await page.goto('/admin/models')
  await page.getByRole('button', { name: '编辑', exact: true }).first().click()
  await expect(page.getByRole('dialog').getByText('上下文容量', { exact: true })).toHaveCount(0)
  expect(errors).toEqual([])
})

test('系统设置分类菜单按需加载并支持直达、返回和窄屏布局', async ({ page }) => {
  await installApiMock(page)
  const requested = []
  page.on('request', request => requested.push(new URL(request.url()).pathname))
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/admin/settings')
  const menu = page.getByRole('navigation', { name: '设置分类' })
  await expect(menu.getByRole('link')).toHaveCount(8)
  await expect(menu.locator('[aria-current="page"]')).toHaveCount(1)
  await expect(page.getByRole('region', { name: '输出大小' })).toBeVisible()
  expect(requested).not.toContain('/api/admin/settings/security')
  expect(requested).not.toContain('/api/admin/settings/quota')
  await menu.getByRole('link', { name: '调用限制', exact: true }).click()
  await expect(page).toHaveURL(/section=quota/)
  await expect(page.locator('.settings-content > .admin-card:visible')).toHaveCount(1)
  const number = page.locator('.settings-content input[role="spinbutton"]').first()
  await number.fill('123')
  await menu.getByRole('link', { name: '安全设置', exact: true }).click()
  await expect(page.getByRole('heading', { name: '安全设置' })).toBeVisible()
  await menu.getByRole('link', { name: '调用限制', exact: true }).click()
  await expect(number).toHaveValue('123')
  await page.goto('/admin/settings?section=observability')
  await expect(page.getByText('最近 24 小时趋势', { exact: true })).toBeVisible()
  await page.reload()
  await expect(menu.getByRole('link', { name: '运行状态', exact: true })).toHaveAttribute('aria-current', 'page')
  await menu.getByRole('link', { name: '存储模式', exact: true }).click()
  await expect(page.getByRole('heading', { name: '存储模式' })).toBeVisible()
  await page.goBack()
  await expect(page.getByRole('heading', { name: '运行状态' })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await menu.getByRole('link', { name: '输出大小', exact: true }).click()
  await expect(page.getByRole('region', { name: '输出大小' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.screenshot({ path: test.info().outputPath('settings-mobile.png'), fullPage: true })
})
test('长回答超过6000Token完整显示和恢复，达到上限时明确提示', async ({ page }) => {
  const state = await installApiMock(page)
  state.reply = '长回答段落。\n\n'.repeat(1500) + '长回答结束标记'
  state.completionTokens = 9000
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/')
  await page.locator('.input-area textarea').fill('输出长回答')
  await page.locator('.send-btn').click()
  await expect(page.getByText('长回答结束标记', { exact: true })).toBeVisible()
  await expect.poll(() => Object.values(state.chats).flat().find(message => message.role === 'assistant')?.content).toBe(state.reply)
  await page.reload()
  await expect(page.getByText('长回答结束标记', { exact: true })).toBeAttached()
  state.reply = '这是一段达到上限的部分回答'
  state.finishReason = 'length'
  await page.locator('.input-area textarea').fill('再次输出')
  await page.locator('.send-btn').click()
  await expect(page.getByText('这是一段达到上限的部分回答', { exact: true })).toBeVisible()
  await expect(page.getByText('回答已达到模型的输出或上下文上限', { exact: false })).toBeVisible()
  // 优化后：上限提示内联展示在同一条回答下方，不得再出现独立的错误气泡消息
  await expect(page.locator('.error-bubble')).toHaveCount(0)
  await expect.poll(() => Object.values(state.chats).flat().find(message => message.content === state.reply)?.status).toBe('length')
})

/** HTTP 正常 EOF 缺少模型结束信号时，保留部分回答、原因与刷新后的继续生成入口。 */
test('无完成信号的部分回答不会误标完成，刷新后可继续生成', async ({ page }) => {
  const state = await installApiMock(page)
  state.reply = '这是未完成回答的前半部分'
  state.finishReason = null
  state.sendDone = false
  await page.addInitScript(() => {
    localStorage.setItem('username', 'admin')
    localStorage.setItem('role', 'admin')
  })
  await page.goto('/')
  await page.locator('.input-area textarea').fill('模拟回答提前结束')
  await page.locator('.send-btn').click()
  await expect(page.locator('.msg-notice')).toContainText('未收到回答完成信号')
  await expect(page.getByRole('button', { name: '基于已生成内容继续', exact: true })).toBeVisible()
  await expect(page.locator('.error-bubble')).toHaveCount(0)
  await expect.poll(() => Object.values(state.chats).flat().find(message => message.role === 'assistant')?.status).toBe('failed')
  await page.reload()
  await expect(page.getByText('这是未完成回答的前半部分', { exact: true })).toBeVisible()
  await expect(page.locator('.msg-notice')).toHaveCount(1)
  await expect(page.locator('.msg-notice')).toContainText('未收到回答完成信号')
  state.reply = '继续补全后的回答'
  state.finishReason = 'stop'
  state.sendDone = true
  await page.getByRole('button', { name: '基于已生成内容继续', exact: true }).click()
  await expect(page.getByText('继续补全后的回答', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '基于已生成内容继续', exact: true })).toHaveCount(0)
  await expect.poll(() => Object.values(state.chats).flat().filter(message => message.role === 'assistant').length).toBe(2)
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
  await page.goto('/admin/settings?section=observability')

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
