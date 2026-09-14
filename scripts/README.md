# 工程运行脚本说明

- 目录用途：存放 chatai-newbot 根工程的本地开发与 Maven 启动辅助脚本，不属于最终用户交付产物。
- 具体内容：`run-maven.mjs`（Maven 命令解析与透传）和 `run-dev.mjs`（前后端开发进程编排）；脚本随项目版本管理，无独立版本号。
- 建立时间：2026-09-04（Asia/Shanghai）。
- 本次使用时间：2026-09-06 至 2026-09-07（Asia/Shanghai）。
- 2026-09-11 使用记录：修复 6000 Token 截断、模型输出配置与不限模式；复用既有脚本执行 Java 21 / Maven 3.9.16 的测试和打包，无安装或升级。
- 同会话后续调整：输出配置集中到系统设置，支持全局缺省及模型可空继承；继续复用本目录的构建入口验证并打包。
- 关联任务：统一工程入口，以及超大文件渐进拆分后的后端编译和测试验证。
- 使用方法：在项目根目录执行 `npm run dev`、`npm run test:server`、`npm run build:server`，或运行 `node scripts/run-maven.mjs <Maven 参数>`。
- 移除方法：先从根目录 `package.json` 的 scripts 中移除相关入口，再删除本目录；直接删除会导致根工程的后端命令失效。

- 2026-09-14 使用记录：上下文大小与输出大小独立配置、草稿中断原因修复。复用原 Node/Maven 构建入口，无新增安装；验证前端 lint/Vitest/Playwright、后端测试和生产打包。

- 2026-09-14 后续使用：修复无完成信号 EOF 误判，重新执行 lint、Vitest、生产 Chromium 和 Maven verify，将所有后续源码修改重新打包。无新增工具或依赖。
