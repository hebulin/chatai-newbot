import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const projectRoot = dirname(scriptDir)
const npmCommand = process.platform === 'win32' ? (process.env.ComSpec || 'cmd.exe') : 'npm'
const nodeCommand = process.execPath
const children = []
let shuttingDown = false

/** 启动一个继承当前终端输入输出的开发子进程。 */
function start(command, args) {
  const child = spawn(command, args, {
    cwd: projectRoot,
    stdio: 'inherit',
    shell: false
  })
  children.push(child)
  child.on('error', error => {
    console.error(`开发进程启动失败：${error.message}`)
    shutdown(1)
  })
  child.on('exit', code => {
    if (!shuttingDown && code !== 0) shutdown(code ?? 1)
  })
  return child
}

/** 统一终止前后端子进程，避免 Ctrl+C 后残留开发服务。 */
function shutdown(exitCode = 0) {
  if (shuttingDown) return
  shuttingDown = true
  for (const child of children) {
    if (!child.killed) child.kill('SIGTERM')
  }
  setTimeout(() => process.exit(exitCode), 200).unref()
}

start(nodeCommand, [join(scriptDir, 'run-maven.mjs'), '-f', 'chatai-with-newbot/pom.xml', 'spring-boot:run'])
start(npmCommand, process.platform === 'win32'
  ? ['/d', '/s', '/c', 'npm --prefix web run dev']
  : ['--prefix', 'web', 'run', 'dev'])
process.on('SIGINT', () => shutdown(0))
process.on('SIGTERM', () => shutdown(0))
