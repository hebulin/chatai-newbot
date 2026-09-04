import { createServer } from 'vite'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 在 Playwright 进程内启动并托管 Vite；返回的清理回调会在全部用例结束后关闭服务，
 * 避免 Windows 下外层命令包装进程残留导致测试不能自然退出。
 */
export default async function globalSetup() {
  const currentDir = dirname(fileURLToPath(import.meta.url))
  const webRoot = resolve(currentDir, '../..')
  const server = await createServer({
    root: webRoot,
    logLevel: 'error',
    server: { host: '127.0.0.1', port: 4173, strictPort: true }
  })
  await server.listen()
  return async () => {
    await server.close()
  }
}
