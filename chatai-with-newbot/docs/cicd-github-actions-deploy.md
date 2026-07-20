# Windows 本地项目通过 GitHub Actions 自动部署 Spring Boot 到 Linux 服务器

> 本文记录一次完整的 CI/CD 搭建过程：Windows 本地开发 Spring Boot 项目，代码推送到 GitHub 指定分支后，由 GitHub Actions 自动构建 JAR，并通过 SSH 部署到 Linux 服务器的 systemd 服务。

## 1. 环境信息

### 1.1 本地环境

- 操作系统：Windows
- 项目路径：`C:\Users\hebul\IdeaProjects\chatai-newbot\chatai-with-newbot`
- GitHub 仓库：`https://github.com/hebulin/chatai-newbot.git`
- 当前部署触发分支：`dev`

### 1.2 项目信息

项目是 Spring Boot + Maven 项目。

`pom.xml` 关键信息：

```xml
<groupId>com.chatai</groupId>
<artifactId>chatai-with-newbot</artifactId>
<version>2.0.1.26.0521</version>
<java.version>21</java.version>
<spring-boot.version>3.3.5</spring-boot.version>
```

应用端口：

```yaml
server:
  port: 9092
```

### 1.3 服务器环境

- 服务器 IP：`101.43.221.155`
- SSH 用户：`hebulin`
- 系统：OpenCloudOS 8.6
- Java：`/usr/lib/jvm/jdk-21.0.6/bin/java`
- 部署目录：`/opt/chatai-with-newbot`
- 日志目录：`/var/log/chatai-with-newbot`
- systemd 服务名：`chatai-with-newbot`

> 安全说明：服务器密码、SSH 私钥、API Key 等敏感信息不要写进仓库，也不要写进博客正文，应统一放到 GitHub Secrets 或服务器环境变量中。

---

## 2. 整体流程设计

最终 CI/CD 流程如下：

1. 本地开发完成后提交代码。
2. 推送到 GitHub 仓库 `dev` 分支。
3. GitHub Actions 自动触发流水线。
4. 流水线拉取代码。
5. 使用 JDK 21 构建 Maven 项目。
6. 生成 Spring Boot fat JAR。
7. 通过 SSH/SCP 上传 JAR 到服务器。
8. 服务器执行部署脚本：
   - 将新 JAR 放入 `releases` 目录；
   - 更新 `current.jar` 软链接；
   - 重启 systemd 服务；
   - 如果失败，尝试回滚到上一个版本。

---

## 3. 服务器准备

### 3.1 创建部署目录和日志目录

服务器上执行：

```bash
sudo mkdir -p /opt/chatai-with-newbot/releases /opt/chatai-with-newbot/shared
sudo mkdir -p /var/log/chatai-with-newbot
sudo chown -R hebulin:hebulin /opt/chatai-with-newbot /var/log/chatai-with-newbot
sudo chmod 755 /opt/chatai-with-newbot /opt/chatai-with-newbot/releases /opt/chatai-with-newbot/shared
sudo chmod 755 /var/log/chatai-with-newbot
```

### 3.2 编写 systemd 服务

创建服务文件：

```bash
sudo vim /etc/systemd/system/chatai-with-newbot.service
```

内容如下：

```ini
[Unit]
Description=chatai-with-newbot Spring Boot Service
After=network.target

[Service]
Type=simple
User=hebulin
Group=hebulin
WorkingDirectory=/opt/chatai-with-newbot
ExecStart=/usr/lib/jvm/jdk-21.0.6/bin/java -jar /opt/chatai-with-newbot/current.jar --spring.profiles.active=prod
SuccessExitStatus=143
Restart=always
RestartSec=10
StandardOutput=append:/var/log/chatai-with-newbot/app.log
StandardError=append:/var/log/chatai-with-newbot/error.log
Environment=TZ=Asia/Shanghai

[Install]
WantedBy=multi-user.target
```

重新加载并设置开机启动：

```bash
sudo systemctl daemon-reload
sudo systemctl enable chatai-with-newbot
```

### 3.3 配置最小化 sudo 权限

GitHub Actions 登录服务器后需要重启服务。如果直接给全部免密 sudo 权限风险较大，所以这里只允许执行指定 systemctl 命令。

创建文件：

```bash
sudo vim /etc/sudoers.d/chatai-with-newbot
```

内容：

```sudoers
hebulin ALL=(root) NOPASSWD: /usr/bin/systemctl daemon-reload, /usr/bin/systemctl restart chatai-with-newbot, /usr/bin/systemctl start chatai-with-newbot, /usr/bin/systemctl stop chatai-with-newbot, /usr/bin/systemctl status chatai-with-newbot, /usr/bin/systemctl is-active chatai-with-newbot, /usr/bin/systemctl enable chatai-with-newbot
```

校验 sudoers 语法：

```bash
sudo chmod 440 /etc/sudoers.d/chatai-with-newbot
sudo visudo -cf /etc/sudoers.d/chatai-with-newbot
```

看到类似输出表示成功：

```text
/etc/sudoers.d/chatai-with-newbot: parsed OK
```

---

## 4. 配置 SSH 部署密钥

### 4.1 在 Windows 本地生成专用部署密钥

PowerShell 执行：

```powershell
ssh-keygen -t ed25519 -f "$env:USERPROFILE\.ssh\chatai_newbot_deploy" -N '""' -C "github-actions-chatai-newbot"
```

会生成：

```text
C:\Users\hebul\.ssh\chatai_newbot_deploy
C:\Users\hebul\.ssh\chatai_newbot_deploy.pub
```

### 4.2 将公钥写入服务器

查看公钥：

```powershell
Get-Content "$env:USERPROFILE\.ssh\chatai_newbot_deploy.pub"
```

把输出内容追加到服务器：

```bash
mkdir -p ~/.ssh
chmod 700 ~/.ssh
vim ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys
```

最终 `authorized_keys` 中有类似内容：

```text
ssh-ed25519 AAAA... github-actions-chatai-newbot
```

### 4.3 本地验证部署密钥

PowerShell 执行：

```powershell
ssh -i "$env:USERPROFILE\.ssh\chatai_newbot_deploy" -o BatchMode=yes hebulin@101.43.221.155 "echo DEPLOY_KEY_OK && whoami"
```

成功输出：

```text
DEPLOY_KEY_OK
hebulin
```

---

## 5. 编写服务器部署脚本

服务器文件：

```text
/opt/chatai-with-newbot/deploy.sh
```

内容如下：

```bash
#!/usr/bin/env bash
set -euo pipefail

APP_NAME="chatai-with-newbot"
APP_DIR="/opt/${APP_NAME}"
RELEASES_DIR="${APP_DIR}/releases"
CURRENT_JAR="${APP_DIR}/current.jar"
SERVICE_NAME="${APP_NAME}"
NEW_RELEASE="${1:-}"

if [[ -z "${NEW_RELEASE}" ]]; then
  echo "Usage: $0 <release-jar-name>"
  exit 1
fi

NEW_JAR="${RELEASES_DIR}/${NEW_RELEASE}"
if [[ ! -f "${NEW_JAR}" ]]; then
  echo "Release jar not found: ${NEW_JAR}"
  exit 1
fi

PREVIOUS_TARGET=""
if [[ -L "${CURRENT_JAR}" ]]; then
  PREVIOUS_TARGET="$(readlink -f "${CURRENT_JAR}")"
elif [[ -f "${CURRENT_JAR}" ]]; then
  PREVIOUS_TARGET="${CURRENT_JAR}"
fi

echo "Deploying ${NEW_JAR}"
ln -sfn "${NEW_JAR}" "${CURRENT_JAR}"

sudo /usr/bin/systemctl daemon-reload
sudo /usr/bin/systemctl restart "${SERVICE_NAME}"
sleep 8

if sudo /usr/bin/systemctl is-active --quiet "${SERVICE_NAME}"; then
  echo "Deploy success: ${NEW_RELEASE}"
  ls -1t "${RELEASES_DIR}"/*.jar 2>/dev/null | tail -n +6 | xargs -r rm -f
  exit 0
fi

echo "Deploy failed, printing service status..."
sudo /usr/bin/systemctl status "${SERVICE_NAME}" --no-pager || true

echo "Trying rollback..."
if [[ -n "${PREVIOUS_TARGET}" && -f "${PREVIOUS_TARGET}" ]]; then
  ln -sfn "${PREVIOUS_TARGET}" "${CURRENT_JAR}"
  sudo /usr/bin/systemctl restart "${SERVICE_NAME}"
  sleep 8
  sudo /usr/bin/systemctl is-active --quiet "${SERVICE_NAME}" && echo "Rollback success" && exit 1
fi

echo "Rollback unavailable or failed"
exit 1
```

赋权：

```bash
chmod +x /opt/chatai-with-newbot/deploy.sh
```

---

## 6. 配置 GitHub Secrets

进入 GitHub 仓库：

```text
https://github.com/hebulin/chatai-newbot
```

路径：

```text
Settings → Secrets and variables → Actions → New repository secret
```

需要新增 3 个 Secret：

| Secret 名称 | Secret 值 |
|---|---|
| `SERVER_HOST` | `101.43.221.155` |
| `SERVER_USER` | `hebulin` |
| `SERVER_SSH_KEY` | `C:\Users\hebul\.ssh\chatai_newbot_deploy` 私钥完整内容 |

查看私钥内容：

```powershell
Get-Content "$env:USERPROFILE\.ssh\chatai_newbot_deploy" -Raw
```

把从：

```text
-----BEGIN OPENSSH PRIVATE KEY-----
```

到：

```text
-----END OPENSSH PRIVATE KEY-----
```

完整复制到 `SERVER_SSH_KEY`。

> 注意：私钥只能放在 GitHub Secrets 中，不要提交到 Git 仓库，不要写进 Markdown 文档。

截图建议：

- `docs/images/github-settings.png`：仓库 Settings 页面。
- `docs/images/github-actions-secrets.png`：Actions Secrets 页面。
- `docs/images/github-new-secret.png`：新增 Secret 页面。

---

## 7. 编写 GitHub Actions Workflow

在项目中新增文件：

```text
.github/workflows/deploy.yml
```

内容如下：

```yaml
name: Build and Deploy chatai-with-newbot

on:
  push:
    branches:
      - dev
  workflow_dispatch:

concurrency:
  group: chatai-with-newbot-deploy
  cancel-in-progress: true

jobs:
  build-and-deploy:
    name: Build and Deploy
    runs-on: ubuntu-latest

    steps:
      - name: Checkout source code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: maven

      - name: Build Spring Boot JAR
        run: mvn -B clean package -DskipTests

      - name: Prepare SSH key
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.SERVER_SSH_KEY }}" > ~/.ssh/deploy_key
          chmod 600 ~/.ssh/deploy_key
          ssh-keyscan -H "${{ secrets.SERVER_HOST }}" >> ~/.ssh/known_hosts

      - name: Upload JAR to server
        run: |
          RELEASE_NAME="chatai-with-newbot-${{ github.sha }}.jar"
          scp -i ~/.ssh/deploy_key \
            target/chatai-with-newbot-*.jar \
            "${{ secrets.SERVER_USER }}@${{ secrets.SERVER_HOST }}:/opt/chatai-with-newbot/releases/${RELEASE_NAME}"

      - name: Deploy on server
        run: |
          RELEASE_NAME="chatai-with-newbot-${{ github.sha }}.jar"
          ssh -i ~/.ssh/deploy_key \
            "${{ secrets.SERVER_USER }}@${{ secrets.SERVER_HOST }}" \
            "bash /opt/chatai-with-newbot/deploy.sh ${RELEASE_NAME}"
```

---

## 8. 提交并推送代码

本地执行：

```powershell
git add .github/workflows/deploy.yml scripts/deploy.sh docs/cicd-github-actions-deploy.md
git commit -m "ci: add GitHub Actions deployment pipeline"
git push origin dev
```

推送后，GitHub Actions 会自动运行。

也可以在 GitHub 网页手动触发：

```text
Actions → Build and Deploy chatai-with-newbot → Run workflow → dev
```

---

## 9. 验证部署结果

### 9.1 查看 GitHub Actions

进入：

```text
GitHub 仓库 → Actions → Build and Deploy chatai-with-newbot
```

确认以下步骤全部成功：

- Checkout source code
- Set up JDK 21
- Build Spring Boot JAR
- Prepare SSH key
- Upload JAR to server
- Deploy on server

截图建议：

- `docs/images/actions-running.png`：流水线运行中。
- `docs/images/actions-success.png`：流水线成功。

### 9.2 在服务器查看服务状态

```bash
sudo systemctl status chatai-with-newbot --no-pager
```

查看日志：

```bash
tail -n 100 /var/log/chatai-with-newbot/app.log
tail -n 100 /var/log/chatai-with-newbot/error.log
```

查看部署目录：

```bash
ls -l /opt/chatai-with-newbot
ls -lh /opt/chatai-with-newbot/releases
```

### 9.3 访问应用

应用端口是 `9092`，如果服务器安全组和防火墙已放行，可访问：

```text
http://101.43.221.155:9092
```

如果无法访问，优先检查：

1. 云服务器安全组是否放行 `9092`。
2. 服务器防火墙是否放行 `9092`。
3. 应用是否监听 `0.0.0.0:9092`。
4. systemd 服务日志是否报错。

---

## 10. 常见问题排查

### 10.1 GitHub Actions SSH 失败

常见原因：

- `SERVER_SSH_KEY` 私钥复制不完整。
- 私钥换行丢失。
- 服务器 `~/.ssh/authorized_keys` 权限不是 `600`。
- 服务器 `~/.ssh` 权限不是 `700`。
- `SERVER_USER` 或 `SERVER_HOST` 配错。

检查服务器权限：

```bash
ls -ld ~/.ssh
ls -l ~/.ssh/authorized_keys
```

正确权限：

```text
drwx------ ~/.ssh
-rw------- authorized_keys
```

### 10.2 systemctl restart 需要密码

说明 sudoers 没配好，检查：

```bash
sudo visudo -cf /etc/sudoers.d/chatai-with-newbot
sudo -l
```

确保包含：

```text
NOPASSWD: /usr/bin/systemctl restart chatai-with-newbot
```

### 10.3 应用启动失败

查看错误日志：

```bash
sudo systemctl status chatai-with-newbot --no-pager
tail -n 200 /var/log/chatai-with-newbot/error.log
```

常见原因：

- 端口 `9092` 被占用。
- 配置文件缺少生产环境参数。
- Java 路径错误。
- JAR 文件损坏或没有上传完整。

### 10.4 Maven 构建失败

GitHub Actions 中使用：

```yaml
java-version: '21'
```

如果依赖下载失败，可以重新运行 workflow；如果测试失败，当前示例使用了：

```bash
mvn -B clean package -DskipTests
```

---

## 11. 最终效果

完成后，每次执行：

```powershell
git push origin dev
```

GitHub Actions 都会自动：

1. 构建 Spring Boot JAR；
2. 上传到服务器；
3. 重启 `chatai-with-newbot` 服务；
4. 保留最近 5 个发布版本；
5. 部署失败时尝试回滚。

这套方式简单、清晰，并且没有把服务器密码或 SSH 私钥提交到仓库，适合个人项目或中小型 Spring Boot 服务部署。

