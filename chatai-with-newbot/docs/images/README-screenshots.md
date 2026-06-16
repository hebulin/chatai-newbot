# GitHub Secrets 配置截图清单

由于 Repository Secrets 需要 GitHub 登录态和网页权限，以下 3 张截图需要在 GitHub 网页完成配置时保存：

1. github-settings.png
   - 页面：仓库首页 → Settings

2. github-actions-secrets.png
   - 页面：Settings → Secrets and variables → Actions
   - 需要能看到 Repository secrets 区域

3. github-new-secret.png
   - 页面：New repository secret
   - 需要分别创建：SERVER_HOST、SERVER_USER、SERVER_SSH_KEY
   - 注意截图时不要暴露 SERVER_SSH_KEY 的明文内容

建议截图路径：

- docs/images/github-settings.png
- docs/images/github-actions-secrets.png
- docs/images/github-new-secret.png
