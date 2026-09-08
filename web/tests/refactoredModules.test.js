// @vitest-environment jsdom
import { createApp, effectScope, nextTick, ref } from 'vue'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { buildChatListProjection, normalizeChatFolders } from '@/stores/chat/chatListProjection'
import { appendMessageVersion, getMessageVersionView, selectMessageVersion } from '@/stores/chat/messageVersions'
import { attachTableDownload, extractTableRows, serializeTableCsv } from '@/composables/markdown/tableExporter'
import { useHtmlPreview } from '@/composables/chat/useHtmlPreview'
import { useMessageSearch } from '@/composables/chat/useMessageSearch'

afterEach(() => {
  document.body.innerHTML = ''
  localStorage.clear()
  vi.restoreAllMocks()
  vi.useRealTimers()
})

/** 挂载 HTML 预览 composable 的最小测试宿主。 */
function mountHtmlPreview() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  let preview
  const app = createApp({
    setup() {
      preview = useHtmlPreview({ isMobile: ref(false) })
      return () => null
    }
  })
  app.mount(host)
  return { app, preview }
}

describe('会话 Store 领域模块', () => {
  it('列表投影保持置顶、文件夹和日期分组语义', () => {
    const result = buildChatListProjection({
      chats: {
        pinned: [{ role: 'user', content: '置顶会话', time: '2026-09-05 10:00:00' }],
        folder: [{ role: 'user', content: '文件夹会话', time: '2026-09-04 10:00:00' }]
      },
      chatSummaries: {},
      chatMeta: { pinned: { pinned: true }, folder: { folderId: 'work' } },
      folders: [{ id: 'work', name: '工作', collapsed: false }],
      searchKeyword: '',
      now: new Date(2026, 8, 5, 12, 0, 0)
    })

    expect(result.total).toBe(2)
    expect(result.groups['置顶'][0].id).toBe('pinned')
    expect(result.folderGroups[0].chats[0].id).toBe('folder')
  })

  it('文件夹规整剔除非法项和重复 ID', () => {
    expect(normalizeChatFolders([
      { id: 'a', name: ' 工作 ' },
      { id: 'a', name: '重复' },
      { id: '', name: '无效' },
      null
    ])).toEqual([{ id: 'a', name: '工作', collapsed: false }])
  })

  it('回答版本追加与切换保持平铺字段兼容', () => {
    const message = { role: 'assistant', content: '旧回答', modelName: '旧模型' }
    const newVersionId = appendMessageVersion(message, {
      content: '新回答',
      modelName: '新模型',
      status: 'done',
      usage: { promptTokens: 10, completionTokens: 5, reasoningTokens: 2, cachedTokens: 1 }
    }, true)

    expect(message.content).toBe('新回答')
    expect(message.promptTokens).toBe(10)
    expect(getMessageVersionView(message).total).toBe(2)
    const oldVersionId = message.versions[0].versionId
    expect(selectMessageVersion(message, oldVersionId)).toBe(true)
    expect(message.content).toBe('旧回答')
    expect(message.currentVersionId).not.toBe(newVersionId)
    expect(message.promptTokens).toBeUndefined()
    expect(message.completionTokens).toBeUndefined()
  })
})

describe('Markdown 表格导出模块', () => {
  it('提取表格文本并正确转义 CSV 特殊字符', () => {
    const table = document.createElement('table')
    table.innerHTML = '<tr><th>名称</th><th>说明</th></tr><tr><td>A</td><td>含,逗号和"引号"</td></tr>'
    table.querySelectorAll('th,td').forEach(cell => {
      Object.defineProperty(cell, 'innerText', { value: cell.textContent })
    })
    const rows = extractTableRows(table)

    expect(rows).toEqual([['名称', '说明'], ['A', '含,逗号和"引号"']])
    expect(serializeTableCsv(rows)).toContain('"含,逗号和""引号"""')
  })

  it('阻止 CSV 公式注入并保留普通负数', () => {
    expect(serializeTableCsv([['=SUM(A1:A2)', '+CMD', '-HYPERLINK()', '@SUM', '-12.5']]))
      .toBe("'=SUM(A1:A2),'+CMD,'-HYPERLINK(),'@SUM,-12.5")
  })

  it('关闭下载菜单时同步移除 document 监听', async () => {
    vi.useFakeTimers()
    const parent = document.createElement('div')
    const wrapper = document.createElement('div')
    const table = document.createElement('table')
    parent.append(wrapper)
    document.body.appendChild(parent)
    const removeListener = vi.spyOn(document, 'removeEventListener')
    attachTableDownload(wrapper, table)
    const button = parent.querySelector('.md-table-btn')

    button.click()
    await vi.runOnlyPendingTimersAsync()
    expect(parent.querySelector('.md-table-menu')).not.toBeNull()
    button.click()

    expect(parent.querySelector('.md-table-menu')).toBeNull()
    expect(removeListener).toHaveBeenCalledWith('click', expect.any(Function))
  })
})

describe('HTML 预览 composable', () => {
  it('限制拖拽比例并保存最终值', async () => {
    const { app, preview } = mountHtmlPreview()
    preview.chatSplitRef.value = {
      getBoundingClientRect: () => ({ left: 0, width: 100 })
    }
    preview.openHtmlPreview('<h1>预览</h1>')
    preview.startDividerDrag({ preventDefault() {} })
    document.dispatchEvent(new MouseEvent('pointermove', { clientX: 95 }))
    document.dispatchEvent(new MouseEvent('pointerup'))
    await nextTick()

    expect(preview.chatPaneStyle.value.flex).toContain('80%')
    expect(localStorage.getItem('htmlPreviewRatio')).toBe('80')
    app.unmount()
  })

  it('系统取消指针或窗口失焦时结束拖拽状态', () => {
    const { app, preview } = mountHtmlPreview()
    preview.chatSplitRef.value = { getBoundingClientRect: () => ({ left: 0, width: 100 }) }
    preview.startDividerDrag({ preventDefault() {} })
    expect(preview.dividerDragging.value).toBe(true)

    window.dispatchEvent(new Event('pointercancel'))
    expect(preview.dividerDragging.value).toBe(false)
    app.unmount()
  })
})

describe('会话内搜索 composable', () => {
  it('支持匹配循环并在切换会话后重置搜索', async () => {
    const messages = ref([
      { role: 'user', content: '目标一' },
      { role: 'assistant', content: '忽略' },
      { role: 'assistant', content: '目标二' }
    ])
    const conversationId = ref('chat-a')
    const onJump = vi.fn(async () => {})
    const scope = effectScope()
    const search = scope.run(() => useMessageSearch({ messages, conversationId, onJump }))

    search.msgSearchOpen.value = true
    search.msgSearchKeyword.value = '目标'
    await nextTick()
    expect(search.activeMsgSearchIndex.value).toBe(0)
    search.nextMsgMatch()
    expect(search.activeMsgSearchIndex.value).toBe(2)
    search.nextMsgMatch()
    expect(search.activeMsgSearchIndex.value).toBe(0)

    conversationId.value = 'chat-b'
    await nextTick()
    expect(search.msgSearchOpen.value).toBe(false)
    expect(search.msgSearchKeyword.value).toBe('')
    scope.stop()
  })
})
