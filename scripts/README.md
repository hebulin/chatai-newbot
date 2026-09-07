# 工程运行脚本说明

- 目录用途：存放 chatai-newbot 根工程的本地开发与 Maven 启动辅助脚本，不属于最终用户交付产物。
- 具体内容：`run-maven.mjs`（Maven 命令解析与透传）和 `run-dev.mjs`（前后端开发进程编排）；脚本随项目版本管理，无独立版本号。
- 建立时间：2026-09-04（Asia/Shanghai）。
- 本次使用时间：2026-09-06 至 2026-09-07（Asia/Shanghai）。
- 关联任务：统一工程入口，以及超大文件渐进拆分后的后端编译和测试验证。
- 使用方法：在项目根目录执行 `npm run dev`、`npm run test:server`、`npm run build:server`，或运行 `node scripts/run-maven.mjs <Maven 参数>`。
- 移除方法：先从根目录 `package.json` 的 scripts 中移除相关入口，再删除本目录；直接删除会导致根工程的后端命令失效。
