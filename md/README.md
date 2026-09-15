# picknear 项目文档索引

> 本项目文档统一存放在 `md/` 目录下，按模块分子目录管理。

---

## 📂 目录结构

```text
md/
├── README.md                          ← 本索引文件
├── 后端项目简历亮点.md                 ← 全后端简历亮点（面试用）
├── 规范/                              ← 开发规范
├── agent/                             ← AI Agent 对话模块
│   └── observability/                 ← Agent 观测（Langfuse/OTel/SSE）
├── auth/                              ← 登录/认证/Token
├── arch/                              ← 架构设计/审查/优化方案
├── report/                            ← 报告/亮点/优化记录
└── ops/                               ← 运维/部署/前端
```

---

## 📋 文档一览

### 🤖 Agent 模块：入口与执行链路

| 文档 | 说明 |
|------|------|
| [Agent 模块入口](agent/README.md) | 模块定位、任务路由和迁移状态 |
| [Agent 模块架构设计](agent/Agent模块架构设计.md) | 当前入口、两阶段主链和组件边界 |
| [Agent 执行链设计](agent/Agent执行链设计.md) | 规划路由、工具循环、DAG 和回退路径 |
| [快速开始与配置](agent/runbook/快速开始与配置.md) | 启动、环境变量、SSE 验证和配置导航 |

### 🧠 Agent 模块：上下文 / 记忆

| 文档 | 说明 |
|------|------|
| [Agent 上下文与记忆设计](agent/Agent上下文与记忆设计.md) | AgentContext、历史回放、字符预算和异步压缩 |

### 🔐 Agent 模块：安全 / 诚实机制

| 文档 | 说明 |
|------|------|
| [Agent 安全与审批设计](agent/Agent安全与审批设计.md) | Guard、数据权限和 CONFIRM 审批恢复 |
| [Agent 诚实机制设计](agent/Agent诚实机制设计.md) | 数据意图、工具证据、输出断言和事实账本 |

### 🧪 Agent 模块：评测 / SSE / 其他

| 文档 | 说明 |
|------|------|
| [Agent 评测设计](agent/Agent评测设计文档.md) | Langfuse judge、主字段契约和当前完成范围 |
| [Agent 评测排障](agent/runbook/Agent评测排障.md) | evaluator/rule 配置、验证步骤和已排除问题 |
| [SSE 后端实现规范](agent/SSE后端实现规范.md) | SSE-only 事件协议、任务状态和连接生命周期 |
| [推荐购买 Agent 前端方案](agent/推荐购买Agent前端方案.md) | 前端对话页设计：ChatBubble / AgentResultCard / mock 降级 |
| [Agent 模块简历亮点](agent/Agent模块简历亮点.md) | Agent 模块面试讲点（自我介绍用） |

### 📡 Agent 观测

| 文档 | 说明 |
|------|------|
| [Agent 观测设计](agent/Agent观测设计.md) | span 树、跨线程传播、后端能力和 SSE 根生命周期 |
| [Agent 观测排障](agent/runbook/Agent观测排障.md) | TraceId 断链、主字段和 OTLP 排障 |

### 🔐 认证与登录

| 文档 | 说明 |
|------|------|
| [登录流程](auth/login-process-flow.md) | 用户登录完整时序图（验证码→双 Token 生成） |
| [Token 刷新拦截器流程](auth/refresh-token-interceptor-flow.md) | RefreshTokenInterceptor 校验与刷新的完整流程 |
| [过期 Token 刷新流程](auth/refresh-expired-token-flow.md) | Access Token 过期后通过 Refresh Token 续期 |
| [Login 模块重构方案](auth/login重构方案.md) | v3 — 6 个 Phase：AuthService 抽取、拦截器瘦身、密码登录 |
| [密码登录方案](auth/密码登录方案.md) | Phase 3.4 — BCrypt 升级、账户锁定、频率限制 |

### 🏗️ 架构与设计

| 文档 | 说明 |
|------|------|
| [项目文档](arch/project-document.md) | 项目整体架构说明 |
| [后端架构拆分方案](arch/后端架构拆分方案.md) | 架构拆分设计（设计模式对齐版） |
| [架构毁灭者审查报告](arch/架构毁灭者-代码挑刺专家.md) | 第三方代码审查发现与修复建议 |
| [后端架构审查报告](arch/后端架构审查报告.md) | 全后端架构审查（2026-08）：分层/死代码分级 + 测试便利项 + 上线前 Checklist |
| [其他模块审查 (Blog/Follow)](arch/其他模块审查-blog-follow.md) | Blog/Follow 模块审查报告 |
| [商店通用查询接口设计](arch/商店通用查询接口设计方案.md) | 商铺查询接口设计规范 |
| [项目优化方案](arch/项目优化方案.md) | 秒杀/商铺/Upload/RabbitMQ 等非 Login 模块问题 |

### 📊 报告与亮点

| 文档 | 说明 |
|------|------|
| [后端项目简历亮点](后端项目简历亮点.md) | 全后端简历亮点（面试自我介绍用） |
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
| [VM 运维手册](../../vm-docs/VM运维手册.md) | 开发/测试环境运维说明（归档于 `vm-docs/`） |
| [日志查看与配置指南](ops/日志查看与配置指南.md) | 日志级别分层、启动时开关、调日志常见坑 |
| [Docker 部署指南](ops/Docker部署指南.md) | 镜像构建 + 部署全流程（缓存管理、网桥排查） |
| [服务器镜像部署指南](ops/服务器镜像部署指南.md) | 服务器不构建镜像，从阿里云 ACR 拉取部署（Pull 模式） |
| [CI 流水线说明](ops/CI流水线.md) | GitHub Actions 自动编译测试 + 按需构建镜像推 ACR |
| [Langfuse 云接入说明](ops/Langfuse云接入说明.md) | OTLP 接入、配额和冒烟验证 |
| [Langfuse MCP 接入与使用指南](ops/Langfuse MCP 接入与使用指南.md) | MCP 查询与管理 |
| [Langfuse CLI 使用指南](ops/Langfuse CLI 使用指南.md) | `lf` 命令行观测与 Prompt 管理 |
| [部署与运维总览](../../vm-docs/部署与运维总览.md) | 跨仓库部署与运维统一入口（后端镜像链路 / VM watchtower / 手动兜底） |

### 📐 开发规范

| 文档 | 说明 |
|------|------|
| [文档规范](规范/文档规范.md) | 文档类型、状态、元数据、索引、按需加载与完成条件 |
| [Git 提交规范](规范/git规范.md) | Commit Message 格式、Type/Scope 定义、提交粒度 |
| [请求头设计规范](规范/请求头设计规范.md) | Token 刷新场景分析、安全决策、前后端对接规范 |

---

## 📝 文档编写约定

> 修改任意文档前，必须先读 [文档规范](规范/文档规范.md) 和目标模块的 `README.md`。

1. 根 README 只列模块；主题索引和任务路由放在模块 `README.md`。
2. 状态只使用 `draft`、`current`、`legacy`；当前文档必须能从模块 README 到达。
3. 元数据只保留 `status` 和按需使用的 `source_of_truth`、`superseded_by`。
4. 新增、删除或重命名当前文档时，必须同步模块 README、相对链接和事实源。
5. 修改完成后运行 `python scripts/docs/check_docs.py`，并修正所有确定性问题。
