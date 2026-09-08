import { build, preview } from 'vite'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 构建生产包并在 Playwright 进程内托管预览，直接验证分块及实际网络加载行为；
 * 避免 Windows 下外层命令包装进程残留导致测试不能自然退出。
 */
export default async function globalSetup() {
  const currentDir = dirname(fileURLToPath(import.meta.url))
  const webRoot = resolve(currentDir, '../..')
  await build({ root: webRoot, logLevel: 'warn' })
  const server = await preview({
    root: webRoot,
    logLevel: 'error',
    preview: { host: '127.0.0.1', port: 4173, strictPort: true }
  })
  return async () => {
    await new Promise((resolveClose, reject) => {
      server.httpServer.close(error => error ? reject(error) : resolveClose())
      server.httpServer.closeAllConnections()
    })
  }
}
