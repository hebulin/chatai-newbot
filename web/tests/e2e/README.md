# Playwright 端到端测试说明

- 目录用途：存放 chatai-newbot 前端浏览器端到端测试，不属于最终用户产物。
- 测试依赖：`@playwright/test` 1.62.1；由 `web/package.json` 和 `web/package-lock.json` 锁定。
- 安装时间：2026-09-04（Asia/Shanghai）。
- 关联任务：建立管理员登录、SSE 聊天、服务端会话保存与刷新恢复，以及后台持久化指标趋势展示的关键链路测试。
- 2026-09-06 更新：使用 Vite 6.4.3 构建生产包后通过 preview 托管；新增真实 Mermaid 普通图/ELK/思维导图和 XLSX 的网络加载时机及下载验证，保持 API 替身隔离。
- 2026-09-07 更新：新增编辑用户消息后在原会话生成回答分页版本的回归，验证不创建分支、上下文请求、上一/下一版本和刷新后的持久化恢复。
- 使用方法：在 `web` 目录执行 `npx playwright install chromium` 安装浏览器，然后执行 `npm run test:e2e`；需要观察浏览器时执行 `npm run test:e2e:headed`。
- 卸载方法：执行 `npm uninstall --save-dev @playwright/test`，并按需删除本目录及 `playwright.config.js`。
