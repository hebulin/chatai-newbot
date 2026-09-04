import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { afterEach, describe, expect, it } from 'vitest'
import { adminApiMessages, adminApiText, adminText, setLocale } from '../src/i18n'
import { ADMIN_EN } from '../src/i18n/admin.en'

const webRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const adminSources = [
  path.join(webRoot, 'src/layout/AdminLayout.vue'),
  path.join(webRoot, 'src/components/admin/AdminPager.vue'),
  ...fs.readdirSync(path.join(webRoot, 'src/views/admin'))
    .filter(name => name.endsWith('.vue'))
    .map(name => path.join(webRoot, 'src/views/admin', name))
]

/** 收集后台源码中以字符串字面量传入国际化函数的中文源文案。 */
function collectMappedSources(source) {
  const keys = new Set()
  const patterns = [
    /(?:\$adminText|\badminText)\(\s*(['"])(.*?)\1/g,
    /\badminApiText\([^,]+,\s*(['"])(.*?)\1/g,
    /\badminApiMessages\([^,]+,[^,]+,\s*(['"])(.*?)\1/g
  ]
  patterns.forEach(pattern => {
    for (const match of source.matchAll(pattern)) keys.add(match[2])
  })
  return keys
}

describe('后台管理国际化', () => {
  afterEach(() => setLocale('zh'))

  /** 验证后台源文案、插值参数与英文回退能同步切换。 */
  it('可在中英文之间切换后台文案', () => {
    setLocale('en')
    expect(adminText('模型管理')).toBe('Models')
    expect(adminText('已删除 {count} 个模型', { count: 3 })).toBe('Deleted 3 models')
    expect(adminApiText('服务端中文消息', '保存成功')).toBe('Saved successfully')

    setLocale('zh')
    expect(adminText('模型管理')).toBe('模型管理')
    expect(adminApiText('服务端中文消息', '保存成功')).toBe('服务端中文消息')
  })

  /** 验证中文模式保留多条接口提示，英文模式不会泄漏仅有中文的服务端文本。 */
  it('按当前语言处理接口多消息', () => {
    setLocale('zh')
    expect(adminApiMessages(['第一条', '第二条'], '', '保存成功')).toBe('第一条；第二条')

    setLocale('en')
    expect(adminApiMessages(['第一条', '第二条'], '保存完成', '保存成功')).toBe('Saved successfully')
  })

  /** 防止后台页面新增英文切换调用却遗漏对应词典项。 */
  it('所有静态后台源文案都有英文映射', () => {
    const missing = new Set()
    adminSources.forEach(file => {
      collectMappedSources(fs.readFileSync(file, 'utf8')).forEach(key => {
        if (!Object.prototype.hasOwnProperty.call(ADMIN_EN, key)) missing.add(key)
      })
    })
    expect([...missing]).toEqual([])
  })
})
