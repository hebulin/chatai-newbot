# Chatai With Newbot

多厂商 AI 大模型聊天平台，支持动态模型配置、权限管理、流式输出、深度思考模式。

## 功能特性

- **多厂商模型接入**：支持 DeepSeek、通义千问 (Qwen)、Kimi、智谱 (GLM)、MiniMax、豆包 (火山引擎) 等 6 大厂商，以及自定义接入
- **动态模型配置**：所有模型配置通过管理后台动态管理，无需修改代码或配置文件
- **思考模式**：根据模型能力自动识别是否支持思考模式，用户可在对话中自由切换
- **登录系统**：用户注册/登录认证，单 IP 每日注册上限 5 个，内置 admin 管理员账户
- **管理后台**：admin 可管理模型、用户、权限、查看使用记录
- **模型权限控制**：模型可设为全员可见或限制访问，admin 可为特定用户开放特定模型
- **流式输出**：基于 SSE 的实时流式响应，增量 DOM 更新避免卡顿
- **富内容渲染**：支持 Markdown、代码高亮、Mermaid 图表、表格等
- **一键复制**：消息和代码块均支持一键复制
- **会话管理**：新建/切换/删除会话，会话数据保存在浏览器 localStorage 中
- **会话导出**：一键导出所有会话记录为文本文件
- **响应式设计**：桌面端侧边栏 + 移动端抽屉式导航

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 2.6.13 (Java 1.8) |
| 响应式 | Spring WebFlux (Flux\<String\> 流式输出) |
| 数据存储 | JSON 文件（无数据库，数据目录: `./data/`） |
| 认证 | Token 认证 + SHA-256 密码哈希 |
| 前端 | 原生 HTML/CSS/JS |
| Markdown | marked.js 12.0.0 |
| 代码高亮 | highlight.js 11.9.0 |
| 图表 | mermaid.js 10.9.0 |

## 项目结构

```
chatai-with-newbot/
├── src/main/java/com/chatai/newbot/
│   ├── config/
│   │   ├── AuthInterceptor.java      # Token 认证拦截器
│   │   └── WebConfig.java            # 拦截器注册
│   ├── controller/
│   │   ├── AuthController.java       # 登录/注册/注销
│   │   ├── ChatController.java       # 聊天 & 模型列表
│   │   └── AdminController.java      # 管理后台 API
│   ├── model/
│   │   ├── User.java                 # 用户模型
│   │   ├── ModelConfig.java          # 已配置模型实例
│   │   ├── Provider.java             # 厂商定义
│   │   ├── ProviderModel.java        # 厂商内置模型
│   │   ├── ChatRequest.java          # 聊天请求
│   │   ├── NewBotMessage.java        # 消息体
│   │   └── UsageLog.java             # 使用记录
│   ├── service/
│   │   ├── FileStorageService.java   # 文件存储服务（核心）
│   │   └── UnifiedChatService.java   # 统一聊天服务
│   └── ChataiWithNewbotApplication.java
├── src/main/resources/
│   ├── application.yml               # 应用配置
│   ├── providers.json                # 内置厂商 & 模型定义
│   └── static/
│       ├── index.html                # 聊天主页
│       ├── login.html                # 登录/注册页
│       ├── admin.html                # 管理后台页
│       ├── my_css/
│       │   ├── chat.css              # 聊天页样式
│       │   ├── login.css             # 登录页样式
│       │   └── admin.css             # 管理页样式
│       └── my_js/
│           ├── chat.js               # 聊天页逻辑
│           ├── login.js              # 登录页逻辑
│           └── admin.js              # 管理页逻辑
└── pom.xml
```

## 快速开始

### 环境要求

- JDK 1.8+
- Maven 3.6+

### 安装运行

```bash
git clone https://github.com/hebulin/chatai-newbot.git
cd chatai-newbot/chatai-with-newbot
mvn clean package -DskipTests
java -jar target/chatai-with-newbot-1.0.7.25.04.09.jar
```

或开发模式：

```bash
mvn spring-boot:run
```

访问 `http://localhost:9092`

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
| POST | `/api/auth/login` | 用户登录 |
| POST | `/api/auth/logout` | 用户注销 |
| GET | `/api/auth/me` | 获取当前用户信息 |

### 聊天相关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 发送消息（SSE 流式） |
| GET | `/api/models` | 获取当前用户可见模型 |
| GET | `/api/heartbeat` | 心跳检测 |

### 管理后台（需 admin 权限）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/admin/providers` | 获取内置厂商列表 |
| GET | `/api/admin/models` | 获取所有模型配置 |
| POST | `/api/admin/models` | 添加模型 |
| PUT | `/api/admin/models/{id}` | 编辑模型 |
| DELETE | `/api/admin/models/{id}` | 删除模型 |
| GET | `/api/admin/users` | 获取用户列表 |
| DELETE | `/api/admin/users/{id}` | 删除用户 |
| PUT | `/api/admin/users/{id}/permissions` | 更新用户模型权限 |
| GET | `/api/admin/usage` | 获取使用记录 |

## 数据存储

所有非会话数据保存在 `./data/` 目录下：

| 文件 | 内容 |
|------|------|
| `users.json` | 用户数据（密码 SHA-256 哈希） |
| `models.json` | 模型配置数据 |
| `usage.json` | 使用记录（最多 10000 条） |

会话数据保存在浏览器 localStorage 中，不上传服务器。

## 支持的厂商与模型

详见 [PROVIDERS.md](PROVIDERS.md)

## 许可证

MIT License
