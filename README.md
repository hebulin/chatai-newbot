# Chatai With Newbot

多厂商 AI 大模型聊天平台，前后端分离架构（Spring Boot 3 + Vue 3），支持动态模型配置、权限管理、流式输出、深度思考模式、多模态对话。

## 功能特性

### 对话体验
- **多厂商模型接入**：支持 DeepSeek、通义千问 (Qwen)、Kimi、智谱 (GLM)、MiniMax、豆包 (火山引擎) 等厂商，以及 OpenAI 协议自定义接入
- **流式输出**：基于 SSE 的实时流式响应，增量渲染避免卡顿
- **深度思考模式**：根据模型能力自动识别是否支持思考模式，可在对话中自由切换，思考过程独立折叠展示
- **多模态对话**：支持图片上传与图片理解（视模型能力）
- **消息级操作**：重新生成回答、编辑后重发
- **跨会话全文搜索**：在所有历史会话中搜索消息内容并定位跳转
- **富内容渲染**：Markdown、代码高亮、Mermaid 图表、表格、图片灯箱
- **会话管理**：新建/切换/重命名/删除会话，服务端持久化，多端同步
- **会话导出**：一键导出会话记录

### 账户与安全
- **登录系统**：用户注册/登录认证，单 IP 每日注册上限，内置 admin 管理员账户
- **Token 持久化**：登录态存储于数据库，服务重启不掉线；7 天有效期 + 滑动续期
- **BCrypt 密码哈希**：新密码一律 BCrypt 存储，旧 SHA-256 存量数据登录时透明升级
- **登录防爆破**：连续失败锁定，防止暴力破解
- **API Key 加密存储**：厂商 API Key 使用 AES-256-GCM 加密落盘（密钥文件 `data/apikey.secret`），后台展示自动脱敏，存量明文启动时自动升级为密文
- **每日调用配额**：可按用户设置每日请求上限

### 管理后台
- **模型管理**：动态增删改模型、批量快速接入、设置默认模型、**一键连通性测试**（验证 Key/URL 可用性与延迟）
- **用户管理**：查看用户、重置密码、**禁用/启用账号**（禁用即时踢下线）、删除用户
- **权限控制**：模型可设为全员可见或限制访问，可为特定用户开放特定模型
- **用量统计**：按用户/模型/日期统计 Token 用量与调用明细
- **厂商定制**：厂商显示名/图标可自定义

### 其他
- **双存储引擎**：SQLite（默认，WAL 模式）与 JSON 文件两种存储实现，后台可切换
- **响应式设计**：桌面端侧边栏 + 移动端抽屉式导航，明暗主题切换
- **CI/CD**：GitHub Actions 自动构建部署（见 `chatai-with-newbot/docs/`）

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.5 (Java 21) |
| 响应式 | Spring WebFlux WebClient（SSE 流式输出） |
| 数据存储 | SQLite (JdbcTemplate, WAL) / JSON 文件双实现，数据目录 `./data/` |
| 认证 | Token 认证（持久化 + 滑动续期）+ BCrypt 密码哈希 |
| 前端框架 | Vue 3 + Vite |
| UI 组件 | Element Plus |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| Markdown | marked + highlight.js + mermaid |

## 项目结构

```
chatai-newbot/
├── chatai-with-newbot/                  # 后端 (Spring Boot)
│   ├── src/main/java/com/chatai/newbot/
│   │   ├── config/                      # 认证拦截器、IP 工具、Web 配置
│   │   ├── controller/                  # Auth / Chat / Admin / Usage / SPA 转发
│   │   ├── model/                       # User / ModelConfig / Provider / ChatRequest ...
│   │   └── service/
│   │       ├── StorageManager.java      # 存储门面（SQLite/JSON 切换）
│   │       ├── SqliteStorageService.java
│   │       ├── JsonFileStorageService.java
│   │       ├── UnifiedChatService.java  # 统一聊天服务（多协议适配 + 连通性测试）
│   │       ├── ChatHistoryService.java  # 服务端会话持久化
│   │       └── ApiKeyCrypto.java        # API Key AES-256-GCM 加解密
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── providers.json               # 内置厂商 & 模型定义
│   │   └── static/                      # 前端构建产物（由 web/dist 复制）
│   └── pom.xml
├── web/                                 # 前端 (Vue 3 + Vite)
│   ├── src/
│   │   ├── api/                         # axios 接口封装
│   │   ├── components/chat/             # 聊天组件（消息/输入/侧边栏/设置...）
│   │   ├── composables/                 # 流式聊天 / Markdown / 主题 / 滚动跟随
│   │   ├── stores/                      # Pinia (auth / chat / models)
│   │   ├── views/                       # ChatView / LoginView / admin 后台页面
│   │   └── layout/                      # 管理后台布局
│   └── vite.config.js
└── data/                                # 运行时数据目录（SQLite 库 / JSON / 密钥文件）
```

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.6+
- Node.js 18+（仅前端开发/构建需要）

### 构建运行

```bash
git clone https://github.com/hebulin/chatai-newbot.git
cd chatai-newbot

# 1. 构建前端
cd web
npm install
npm run build          # 产物输出并同步到后端 static 目录

# 2. 构建后端
cd ../chatai-with-newbot
mvn clean package -DskipTests

# 3. 运行
java -jar target/*.jar
```

前端开发模式（热更新，代理到后端 9092 端口）：

```bash
cd web
npm run dev
```

访问 `http://localhost:9092`（开发模式访问 Vite 输出的地址）

### 默认管理员

| 用户名 | 密码 |
|--------|------|
| admin | admin123 |

首次登录后请尽快修改密码。

## API 接口

### 认证相关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册 |
| POST | `/api/auth/login` | 用户登录（含防爆破锁定） |
| POST | `/api/auth/logout` | 用户注销 |
| GET | `/api/auth/me` | 获取当前用户信息 |

### 聊天相关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 发送消息（SSE 流式） |
| GET | `/api/models` | 获取当前用户可见模型 |
| GET/PUT | `/api/chat/history` | 服务端会话读写 |

### 管理后台（需 admin 权限）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/providers` | 获取厂商列表 |
| GET | `/api/admin/models` | 获取所有模型配置（Key 脱敏） |
| POST | `/api/admin/models` | 添加模型 |
| POST | `/api/admin/models/batch` | 批量快速接入 |
| PUT | `/api/admin/models/{id}` | 编辑模型 |
| POST | `/api/admin/models/{id}/test` | 模型连通性测试 |
| DELETE | `/api/admin/models/{id}` | 删除模型 |
| PUT | `/api/admin/models/default` | 设置默认模型 |
| GET | `/api/admin/users` | 获取用户列表 |
| PUT | `/api/admin/users/{id}` | 编辑用户（重置密码/禁用启用/配额） |
| DELETE | `/api/admin/users/{id}` | 删除用户 |
| PUT | `/api/admin/users/{id}/permissions` | 更新用户模型权限 |
| GET | `/api/admin/usage` | 获取使用记录 |

## 数据存储

默认使用 SQLite（`data/chatai.db`，WAL 模式），可在后台切换为 JSON 文件存储：

| 数据 | SQLite | JSON |
|------|--------|------|
| 用户（BCrypt 哈希） | `t_user` | `users.json` |
| 模型配置（Key 加密） | `t_model_config` | `models.json` |
| 登录 Token | `t_token` | `t_token`（恒走 SQLite） |
| 会话记录 | `t_chat_history` | `chat_history/*.json` |
| 用量日志 | `t_usage_log` | `usage_logs_日期.json` |

> ⚠️ `data/apikey.secret` 为 API Key 加密密钥文件，请与数据文件**一同备份**，丢失后已存 Key 无法解密。

## 许可证

MIT License
