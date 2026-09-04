# Chatai With Newbot

多厂商 AI 大模型聊天平台，前后端分离架构（Spring Boot 3 + Vue 3）。支持动态模型配置、SSE 流式输出、深度思考、多模态与附件文档对话、联网搜索、智能体角色、会话分享等能力，开箱即用（空白部署零配置冷启动），用户端界面中英双语。

## 功能特性

### 对话体验
- **多厂商模型接入**：内置 DeepSeek、通义千问 (Qwen)、Kimi、智谱 (GLM)、MiniMax、豆包 (火山引擎) 等厂商，支持 OpenAI 协议自定义接入
- **流式输出**：基于 SSE 的实时流式响应，增量渲染避免卡顿
- **深度思考模式**：按模型能力自动识别，思考过程独立折叠展示（简约无背景风格），支持随时切换
- **联网搜索**：集成 Tavily 搜索，回答前先检索实时网络信息（后台可配置开关与测试）
- **智能体/角色**：内置智能体 + 用户自定义提示词预设（多条保存、单条启用），可按会话绑定角色
- **多模态与附件**：统一附件入口，图片（自动压缩，≤5MB）走多模态理解，文本文档（txt/md/csv/json/doc/docx/xls/xlsx 等，≤3MB）服务端解析为纯文本注入上下文，不依赖模型多模态能力
- **消息级操作**：重新生成回答、编辑后重发、复制、清除上下文（分隔线后不再携带历史）
- **跨会话全文搜索**：在所有历史会话中搜索消息内容并定位跳转
- **富内容渲染**：Markdown、代码高亮、Mermaid 图表、KaTeX 公式、表格（可下载 Excel/CSV）、图片灯箱
- **会话管理**：新建/切换/重命名/删除，服务端按会话行级持久化，多端同步，AI 自动命名
- **会话分享**：生成只读分享链接，可设有效期，个人与后台均可管理
- **界面双语**：用户端与管理后台均支持中英双语（vue-i18n）即时切换
- **响应式设计**：桌面端侧边栏 + 移动端抽屉式导航，明暗主题切换

### 账户与安全
- **登录注册**：自助注册（单 IP 每日注册上限）、内置 admin 管理员账户
- **HttpOnly Cookie 认证**：Token 通过 HttpOnly + SameSite=Lax Cookie 下发，前端 JS 不可读；Token 持久化于数据库，服务重启不掉线，7 天有效期 + 滑动续期
- **BCrypt 密码哈希**：新密码一律 BCrypt 存储，旧 SHA-256 存量数据登录时透明升级
- **登录防爆破**：连续失败锁定，防止暴力破解
- **短期限流**：聊天接口按用户短时窗口限流，防止恶意刷量
- **API Key 加密存储**：厂商 API Key 使用 AES-256-GCM 加密落盘（密钥文件 `data/apikey.secret`），后台展示自动脱敏，存量明文启动时自动升级为密文
- **多维配额与预算**：支持全局每日调用次数、Token 与人民币成本预算，用户可按调用次数或 Token 单独覆盖
- **登录设备管理**：查看各终端登录会话，支持踢下线
- **数据管理**：全量会话导出（TXT/Markdown/JSON 备份）、JSON 备份导入恢复、一键清空

### 管理后台
- **模型管理**：动态增删改、批量快速接入、批量删除、设置默认模型、分项 Token 单价配置、并发连通性测试（延迟/生成速度留存展示）
- **用户管理**：查看、新增、重置密码、禁用/启用（即时踢下线）、删除、按用户设置每日限额
- **权限控制**：模型可设为全员可见或限制访问，可为特定用户开放特定模型
- **用量与成本统计**：按用户/模型/日期查看 Token 与人民币成本，可在 Token/金额间切换；支持配置默认币种及汇率，历史人民币成本实时换算展示
- **审计日志**：登录、模型/用户/分享等关键管理操作全程留痕，按用户/操作类型/日期筛选分页，自动按保留期清理
- **系统公告**：支持公告期与历史公告，用户端弹窗展示（可选不再提示）
- **分享管理**：全站分享链接列表（分页 + 状态筛选），批量删除、一键清除失效
- **厂商定制**：厂商显示名/图标可自定义

### 运维与工程
- **零配置冷启动**：空白部署无需预建任何文件——启动时自动创建 `data/` 目录，sqlite-jdbc 自动创建库文件，幂等建表 + 老库自动迁移（ALTER TABLE 补列），自动播种 admin 账户与各厂商预置模型
- **SQLite 存储**：WAL 模式 + HikariCP 连接池调优，settings/模型/公告等热点数据内存缓存
- **数据自动清理**：用量日志（默认保留 120 天）、过期 Token、过期分享、审计日志定时清理
- **统一异常处理**：`@RestControllerAdvice` 全局异常处理器，接口错误统一返回 `{ success: false, message }`
- **滚动日志**：logback 按天 + 大小滚动（单文件 20MB，gzip 归档，保留 30 天 / 总量 1GB 封顶），日志目录 `logs/` 可用 `-DLOG_PATH` 覆盖
- **CI/CD**：GitHub Actions 自动构建部署，失败自动回滚（见 `chatai-with-newbot/docs/`）

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.3.5 (Java 21) |
| 响应式 | Spring WebFlux WebClient（SSE 流式输出） |
| 数据存储 | SQLite (JdbcTemplate + HikariCP, WAL 模式)，数据目录 `./data/` |
| 认证 | HttpOnly Cookie Token（持久化 + 滑动续期）+ BCrypt 密码哈希 |
| 文档解析 | Apache POI（doc/docx/xls/xlsx）+ 纯文本类格式 |
| 联网搜索 | Tavily Search API |
| 日志 | logback 滚动日志（按天 + 大小，gzip 归档） |
| 前端框架 | Vue 3 + Vite |
| UI 组件 | Element Plus |
| 状态管理 | Pinia |
| 路由 | Vue Router |
| 国际化 | vue-i18n（全站中英双语） |
| 内容渲染 | marked + highlight.js + mermaid + KaTeX + xlsx |

## 项目结构

```
chatai-newbot/
├── chatai-with-newbot/                   # 后端 (Spring Boot)
│   ├── src/main/java/com/chatai/newbot/
│   │   ├── config/                       # 认证拦截器、IP 工具、Web 配置
│   │   ├── controller/                   # Auth / Chat / Admin / Usage / File / Share / SPA 转发
│   │   ├── exception/                    # 全局异常处理器 + 业务异常
│   │   ├── model/                        # User / ModelConfig / PromptPreset / ChatAttachment ...
│   │   └── service/
│   │       ├── StorageManager.java       # 存储门面（直连 SQLite）
│   │       ├── SqliteStorageService.java # SQLite 存储实现（建表迁移 + 内存缓存）
│   │       ├── UnifiedChatService.java   # 统一聊天服务（多协议适配 + 连通性测试）
│   │       ├── ChatHistoryService.java   # 服务端会话持久化（按会话行级存储）
│   │       ├── WebSearchService.java     # Tavily 联网搜索
│   │       ├── DocumentParseService.java # 附件文档解析（POI + 文本类）
│   │       ├── FileStorageService.java   # 上传文件存储（图片 / 附件解析文本）
│   │       ├── BuiltinAgents.java        # 内置智能体预设
│   │       ├── AuditLogService.java      # 审计日志记录与查询
│   │       ├── LoginAttemptService.java  # 登录防爆破
│   │       ├── RateLimitService.java     # 聊天短期限流
│   │       ├── BudgetReservationService.java # 并发预算/用量预占
│   │       ├── ObservabilityService.java # 请求、聊天流与 JVM 运行指标
│   │       ├── DataCleanupService.java   # 过期数据定时清理
│   │       └── ApiKeyCrypto.java         # API Key AES-256-GCM 加解密
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   ├── logback-spring.xml            # 滚动日志配置
│   │   └── providers.json                # 内置厂商 & 模型定义（源码不保存前端 static）
│   ├── scripts/deploy.sh                 # 服务器端部署脚本（软链切换 + 回滚）
│   └── pom.xml
├── web/                                  # 前端 (Vue 3 + Vite)
│   ├── src/
│   │   ├── api/                          # axios 接口封装
│   │   ├── components/chat/              # 聊天组件（消息/输入/侧边栏/设置...）
│   │   ├── composables/                  # 流式聊天 / Markdown / 主题 / 滚动跟随
│   │   ├── i18n/                         # vue-i18n 中英文案字典
│   │   ├── stores/                       # Pinia (auth / chat / models)
│   │   ├── views/                        # ChatView / LoginView / ShareView / admin 后台页面
│   │   └── layout/                       # 管理后台布局
│   └── vite.config.js
└── data/                                 # 运行时数据目录（自动创建：SQLite 库 / 密钥 / 上传文件）
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
npm run lint
npm test
npm run build          # 产物仅输出到 web/dist

# 2. 构建后端（Maven 自动把 ../web/dist 打入 JAR 的 static 目录）
cd ../chatai-with-newbot
mvn clean verify

# 3. 运行（空白部署无需预建任何目录/文件，首次启动自动初始化）
java -jar target/*.jar
```

访问 `http://localhost:9092`。

前端开发模式（热更新，代理到后端 9092 端口）：

```bash
cd web
npm run dev
```

### 首次启动自动完成

- 创建 `data/` 目录与 `chatai.db` 数据库（全部表结构幂等建表）
- 创建默认管理员并播种各厂商预置模型（待启用状态）
- 生成 API Key 加密密钥 `data/apikey.secret`

### 默认管理员

| 用户名 | 密码 |
|--------|------|
| admin | admin123 |

首次登录后请尽快修改密码，并在 后台 → 模型管理 中填入厂商 API Key 启用模型。

## API 接口（概览）

### 认证相关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/register` | 用户注册（单 IP 每日上限） |
| POST | `/api/auth/login` | 用户登录（含防爆破锁定） |
| POST | `/api/auth/logout` | 用户注销 |
| GET | `/api/auth/me` | 获取当前用户信息 |

### 聊天相关

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 发送消息（SSE 流式，含限流/配额校验） |
| GET | `/api/models` | 获取当前用户可见模型 |
| GET/POST | `/api/chat/history` | 服务端会话读写（按会话行级存储） |
| GET | `/api/chat/history/search` | 跨会话全文搜索 |
| POST | `/api/chat/generate-title` | AI 自动命名会话 |
| GET/PUT | `/api/user/prompt-presets` | 提示词预设/智能体读写 |
| POST | `/api/upload/image` | 聊天图片上传 |
| POST | `/api/upload/document` | 附件文档上传解析 |
| POST/GET | `/api/share/*` | 会话分享创建 / 只读访问 |

### 管理后台（需 admin 权限，`/api/admin/*`）

覆盖模型管理（增删改/批量接入/批量删除/连通测试/默认模型）、用户管理（增删改/权限/限额/批量删除）、用量统计（明细/聚合/筛选）、分享管理、审计日志、系统公告、全局配额与联网搜索配置等，完整路径见 `AdminController`。

## 数据存储

统一使用 SQLite（`data/chatai.db`，WAL 模式 + HikariCP），读多写少数据带内存缓存：

| 数据 | 表 |
|------|------|
| 用户（BCrypt 哈希、提示词预设、限额） | `t_user` |
| 模型配置（Key 加密、测试指标、人民币 Token 单价） | `t_model_config` |
| 登录 Token（设备管理） | `t_token` |
| 会话记录（按会话行级存储） | `t_chat_session` / `t_chat_user_state` |
| 会话分享 | `t_chat_share` |
| 系统公告 | `t_announcement` |
| 用量日志（Token 与人民币成本快照，默认保留 120 天） | `t_usage_log` |
| 审计日志（自动按保留期清理） | `t_audit_log` |
| 键值配置 | `t_setting` |

> ⚠️ `data/apikey.secret` 为 API Key 加密密钥文件，请与 `chatai.db`、`data/uploads/` **一同备份**，密钥丢失后已存 Key 无法解密。

## 许可证

MIT License
