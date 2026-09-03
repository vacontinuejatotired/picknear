# Langfuse MCP 接入与使用指南

> **版本**: v1.1（合并接入说明 + 使用指南）  
> **最后更新**: 2026-08-23  
> **上游文档**: [Langfuse云接入说明](./Langfuse云接入说明.md)（M1 链路打通）、[Agent全链路观测架构设计](./Agent全链路观测架构设计.md)  
> **官方文档**: [Langfuse MCP Server](https://langfuse.com/docs/api-and-data-platform/features/mcp-server)

---

> **另有命令行入口（CLI，2026-09-03）**：终端查观测/管 prompt/评分可用 `lf` 封装命令替代 MCP 工具，见 [Langfuse CLI 使用指南](./Langfuse CLI 使用指南.md)。MCP server 保留，两者并存。

## 1. 背景与价值

接入 **Langfuse 官方原生 MCP server** 后，Claude Code 会话内直接可调用 `listTraces` / `listObservations` / `queryMetrics` 等工具查数据，验证埋点结构、token 用量、成本变成一句话的事。

## 2. 现状（已完成 ✅）

- 已实测 MCP 端点握手（HTTP 200），暴露 **79 个工具**（读 + 写）
- 认证复用 `.env` 里现成的 `LANGFUSE_BASIC_AUTH`（无需新增凭据）
- VM 端 + Windows 主机端均可用

## 3. 官方 MCP server 信息

| 项 | 值 |
|----|----|
| 端点（JP 区域） | `https://jp.cloud.langfuse.com/api/public/mcp` |
| Transport | `streamableHttp`（远程 HTTP，**本机无需安装任何东西**） |
| 认证 | Basic Auth（Authorization 头） |
| 架构 | **无状态**：每次请求独立认证，无 session id |
| 工具 | 79 个，读 + 写（观测/prompt/评分/指标/数据集/评估/仪表盘） |

## 4. 配置步骤

### 4.1 前置

`picknear/picknear/.env` 已有：
```
LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY / LANGFUSE_BASE_URL / LANGFUSE_BASIC_AUTH
```

### 4.2 提取认证 token

```bash
B64=$(sed -n 's/.*LANGFUSE_BASIC_AUTH="\([^"]*\)".*/\1/p' .env)
echo "$B64" | base64 -d   # 应输出 pk-lf-...:sk-lf-...
```

> ⚠️ 若 `.env` 被 Windows 编辑器存成 CRLF，先 `tr -d '\r'` 再提取：
> ```bash
> B64=$(tr -d '\r' < .env | sed -n 's/.*LANGFUSE_BASIC_AUTH="\([^"]*\)".*/\1/p')
> ```

### 4.3 注册 MCP（用户级，推荐）

```bash
claude mcp add --scope user --transport http langfuse \
    "https://jp.cloud.langfuse.com/api/public/mcp" \
    --header "Authorization: Basic $B64"
```

- `--scope user`：任何目录启动的 Claude Code 会话都可用
- 若不加 `--scope`，默认写入当前项目作用域

### 4.4 验证与生效

```bash
claude mcp list     # 预期：langfuse ... (HTTP) - ✔ Connected
```

**⚠️ 配置后必须重启 Claude Code 会话**（`/exit` 后重新 `claude`）。

## 5. 作用域说明

| scope | 配置位置 | 适用 |
|-------|----------|------|
| `user` | `~/.claude.json` 顶层 `mcpServers` | ✅ **推荐** |
| `project` | `~/.claude.json` 的 `projects.<项目根>` | 仅在该项目目录 |

**踩坑**：项目级 scope 下，会话从工作区根启动时 MCP 不加载。已改为 `user` scope 解决。

## 6. 安全

- 密钥只存在 `~/.claude.json`，**不进任何 git 仓库**
- 79 个工具默认读 + 写全开。只读场景建议配置 **allowlist**（`list*` / `get*` / `queryMetrics`）
- `submitFeedback` 调用前会要求用户确认

## 7. 使用指南

### 7.1 能力总览

| 能力组 | 常用工具 | 用途 |
|--------|----------|------|
| **观测 / trace** | `listObservations`、`getObservation` | 查 trace 与 span（含输入/输出/usage/耗时） |
| **指标 / 分析** | `queryMetrics`、`getMetricsSchema` | token 用量、成本、延迟、错误率等聚合分析 |
| **Prompt 管理** | `listPrompts`、`getPrompt` | 看项目里的 prompt 版本与内容 |
| **评分 / 反馈** | `listScores`、`getScore`、`createScore` | 给 trace/observation 打质量分 |
| **数据集 / 实验** | `listDatasets`、`listExperiments` | 评估集与实验运行 |
| **健康检查** | `getHealth` | 确认 Langfuse 云端可用 |

### 7.2 高频场景

**"看看最近的调用情况"** → `listObservations`（按时间范围过滤）

**"展开某条 trace 看完整结构"** → `listObservations`（传 `traceId`），预期结构：
```
agent.session  (SPAN, 根)
├── agent.prompt_hook  (SPAN)
└── agent.phase1  (SPAN)
    ├── chat qwen-plus  (GENERATION, 含 in/out token)
    └── agent.decision  (SPAN)
```

**"这个月的 token 用量"** → `queryMetrics`（view=observations）+ `getMetricsSchema`

**"看某个 span 的输入输出"** → `getObservation`（传 observation id）

**"给这条调用打个分"** → `createScore`（traceId + 名称 + 值）

> MCP 工具中**没有独立的 `getTrace`**，查完整 trace 用 `listObservations` 按 `traceId` 过滤即可。

### 7.3 REST API 兜底

MCP 本质封装 Langfuse 公共 API。一条 curl 即可查：

```bash
PK=$(sed -n 's/.*LANGFUSE_PUBLIC_KEY="\([^"]*\)".*/\1/p' .env)
SK=$(sed -n 's/.*LANGFUSE_SECRET_KEY="\([^"]*\)".*/\1/p' .env)

# 最近 5 条 trace
curl -su "$PK:$SK" "https://jp.cloud.langfuse.com/api/public/traces?limit=5"
```

REST API 有**完整的 trace 端点**（`GET /api/public/traces/{id}` 含 `observations` 数组），比 MCP 更适合拿整棵树。

### 7.4 免费档注意事项

| 项 | 说明 |
|----|------|
| 读取配额 | 读 trace/observation/metrics **不消耗** 50,000 units/月的摄取配额 |
| 删除 API | 免费档 50 req/day，`delete*` 工具慎用 |
| 网页端并存 | MCP 适合快速查证；网页端（jp.cloud.langfuse.com）仍是可视化主入口 |

## 8. 排查

| 现象 | 原因 | 处理 |
|------|------|------|
| `claude mcp list` 显示 Connected 但会话内无工具 | 项目级 scope + cwd 不匹配 | 改 `user` scope，重启会话 |
| curl 返回 401 | 认证头带 `\r`（CRLF）或 token 提取错误 | 用 §4.2 的 `tr -d '\r'` |
| 想知道端点是否可达 | 发 MCP initialize 请求 | `curl -X POST <endpoint>` 带 Basic 头 + `initialize` 方法 |
