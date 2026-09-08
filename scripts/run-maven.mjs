import { existsSync } from 'node:fs'
import { spawn } from 'node:child_process'
import { join } from 'node:path'

/**
 * 解析 Maven 命令：显式 MAVEN_CMD 优先，其次 MAVEN_HOME，最后回退到 PATH。
 * Windows 开发机额外兼容项目当前约定的 D 盘 Maven 安装目录。
 */
function resolveMavenCommand() {
  if (process.env.MAVEN_CMD) return process.env.MAVEN_CMD
  if (process.env.MAVEN_HOME) {
    const command = join(process.env.MAVEN_HOME, 'bin', process.platform === 'win32' ? 'mvn.cmd' : 'mvn')
    if (existsSync(command)) return command
  }
  if (process.platform === 'win32') {
    const workspaceMaven = 'D:\\apache-maven-3.9.16\\bin\\mvn.cmd'
    if (existsSync(workspaceMaven)) return workspaceMaven
    return 'mvn.cmd'
  }
  return 'mvn'
}

/** 对传给 cmd.exe 的固定工程参数做安全引用，避免空格或元字符改变命令边界。 */
function quoteWindowsArgument(value) {
  const text = String(value)
  if (!/[\s&|<>^()%!"]/u.test(text)) return text
  return `"${text.replaceAll('"', '""')}"`
}

/** 启动 Maven 子进程并把退出码原样返回给 npm。 */
function run() {
  const command = resolveMavenCommand()
  const inputArgs = process.argv.slice(2)
  const windows = process.platform === 'win32'
  const executable = windows ? (process.env.ComSpec || 'cmd.exe') : command
  const args = windows
    ? ['/d', '/s', '/c', [command, ...inputArgs].map(quoteWindowsArgument).join(' ')]
    : inputArgs
  const child = spawn(executable, args, {
    stdio: 'inherit',
    shell: false
  })
  child.on('error', error => {
    console.error(`无法启动 Maven：${error.message}`)
    process.exitCode = 1
  })
  child.on('exit', (code, signal) => {
    if (signal) {
      console.error(`Maven 被信号 ${signal} 终止`)
      process.exitCode = 1
      return
    }
    process.exitCode = code ?? 1
  })
}

run()
