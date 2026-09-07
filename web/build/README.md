# 构建检查工具

- 用途：项目内构建工具模块，随源码版本管理，无独立安装或版本号。
- 内容：`bundleBudget.js`，为 Vite 6.4.3 / Node.js 提供主入口与聊天页的 raw/gzip 体积预算，以及按需重依赖的静态引用检查。
- 建立时间：2026-09-06，关联任务为超大文件拆分与前端按需加载优化；无新增第三方依赖。
- 使用：`npm --prefix web run build` 或 `npm run check` 自动执行；阈值在 `DEFAULT_BUNDLE_BUDGETS` 配置。
- 移除：先移除 `web/vite.config.js` 中插件引用和相关预算测试，再移除本目录。常规开发应保留此构建门禁。
