# picknear 项目文档索引

> 本项目文档统一存放在 `md/` 目录下，按模块分子目录管理。

---

## 📂 目录结构

```
md/
├── README.md                 ← 本索引文件
├── 规范/                     ← 开发规范
├── agent/                    ← AI Agent 对话模块
├── auth/                     ← 登录/认证/Token
├── arch/                     ← 架构设计/审查/优化方案
├── report/                   ← 报告/亮点/优化记录
└── ops/                      ← 运维/部署/前端
```

---

## 📋 文档一览

### 🤖 Agent 模块

| 文档 | 说明 |
|------|------|
| [Agent 模块架构设计](agent/Agent模块架构设计.md) | 六层架构详解：注解层→配置层→控制层→服务层→工具层→上下文层 |
| [SSE 后端实现规范](agent/SSE后端实现规范.md) | SSE 内容协商、数据格式、SseEmitter 配置、错误处理 |
| [SSE 流式读取方案](agent/SSE流式读取方案.md) | 前端 fetch + ReadableStream 读取 SSE 流 |
| [推荐购买 Agent 前端方案](agent/推荐购买Agent前端方案.md) | 前端对话页设计：ChatBubble / AgentResultCard / mock 降级 |
| [Agent 历史会话实现方案](agent/Agent历史会话实现方案.md) | 历史会话：agent_conversation/agent_message 两表、会话列表 + 点进查看 |

### 🔐 认证与登录

| 文档 | 说明 |
|------|------|
| [登录流程](auth/login-process-flow.md) | 用户登录完整时序图（验证码→双Token生成） |
| [Token 刷新拦截器流程](auth/refresh-token-interceptor-flow.md) | RefreshTokenInterceptor 校验与刷新的完整流程 |
| [过期 Token 刷新流程](auth/refresh-expired-token-flow.md) | Access Token 过期后通过 Refresh Token 续期 |
| [Login 模块重构方案](auth/login重构方案.md) | v3 — 6 个 Phase，含 AuthService 抽取、拦截器瘦身、密码登录 |
| [密码登录方案](auth/密码登录方案.md) | Phase 3.4 — BCrypt 升级、账户锁定、频率限制 |

### 🏗️ 架构与设计

| 文档 | 说明 |
|------|------|
| [项目文档](arch/project-document.md) | 项目整体架构说明 |
| [架构毁灭者审查报告](arch/架构毁灭者-代码挑刺专家.md) | 第三方代码审查发现与修复建议 |
| [其他模块审查 (Blog/Follow)](arch/其他模块审查-blog-follow.md) | Blog/Follow 模块审查报告 |
| [商店通用查询接口设计](arch/商店通用查询接口设计方案.md) | 商铺查询接口设计规范 |
| [项目优化方案](arch/项目优化方案.md) | 秒杀/商铺/Upload/RabbitMQ 等非 Login 模块问题 |

### 📊 报告与亮点

| 文档 | 说明 |
|------|------|
| [项目亮点 - 上台讲解](report/项目亮点-上台讲解.md) | 项目亮点演示文稿 |
| [项目亮点 - 讲解逐字稿](report/项目亮点-讲解逐字稿.md) | 亮点讲解逐字稿 |
| [下单优化压测报告](report/下单优化压测报告.md) | 秒杀场景 Redis+MQ 异步落库压测数据 |
| [项目优化记录](report/项目优化记录.md) | Phase 0-4 已落地优化项清单 |

### 🛠️ 运维与前端

| 文档 | 说明 |
|------|------|
| [前端开发文档](ops/前端开发文档.md) | 完整 API 接口文档（含数据模型、认证、分页） |
| [阿里云 OSS 图片上传方案](ops/阿里云OSS图片上传方案.md) | FileService 接口设计、本地/OSS 双实现 |
| [博客图片上传方案](ops/博客图片上传方案.md) | 博客图片上传流程 |
| [虚拟机连接指南](../../vm-docs/虚拟机连接指南.md) | 开发/测试环境连接说明（VM 专属文档已归档到 `vm-docs/`） |
| [日志查看与配置指南](ops/日志查看与配置指南.md) | 日志级别分层、启动时开关、调日志常见坑 |
| [Docker 部署指南](ops/Docker部署指南.md) | 镜像构建 + 部署全流程（build.sh 本地构建、缓存管理、网桥排查） |
| [服务器镜像部署指南](ops/服务器镜像部署指南.md) | 服务器不构建镜像，从阿里云 ACR 拉取现成镜像部署（Pull 模式） |

### 📐 开发规范

| 文档 | 说明 |
|------|------|
| [Git 提交规范](规范/git规范.md) | Commit Message 格式、Type/Scope 定义、提交粒度 |
| [请求头设计规范](规范/请求头设计规范.md) | Token 刷新场景分析、安全决策、前后端对接规范 |

---

## 📝 文档编写约定

1. 新增文档请在本索引中添加条目
2. 文档使用 Markdown 格式，存放在对应子目录下
3. 涉及 API 变更时同步更新 `前端开发文档.md`
4. 涉及架构变更时更新对应流程文档
5. 子目录间引用使用相对路径，如 `[Agent架构](../agent/Agent模块架构设计.md)`
