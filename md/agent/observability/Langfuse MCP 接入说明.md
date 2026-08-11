# Langfuse MCP 接入说明

> **版本**: v1.0
> **最后更新**: 2026-08-05
> **上游文档**: [Langfuse云接入说明](./Langfuse云接入说明.md)（M1 链路打通）、[Agent全链路观测架构设计](./Agent全链路观测架构设计.md)
> **官方文档**: [Langfuse MCP Server](https://langfuse.com/docs/api-and-data-platform/features/mcp-server)
> **用途**: 让 Claude Code 直接查询 Langfuse 观测数据（trace / span / token 用量），替代"贴日志 / 网页截图"的人工核对

---

## 1. 背景与价值

观测链路（M1）打通后，trace 已能到达 Langfuse 云，但验证期每次要看调用情况都得：
- 打开 Langfuse 网页手动翻，或
- 让后端同学贴日志，或
- 手动 curl REST API

接入 **Langfuse 官方原生 MCP server** 后，Claude Code 会话内直接可调用 `listTraces` / `listObservations` / `queryMetrics` 等工具查数据，验证埋点结构、token 用量、成本变成一句话的事。

## 2. 现状（已完成 ✅）

- 2026-08-05 完成配置，`claude mcp list` 显示 `langfuse ... ✔ Connected`
- **2026-08-09 主机端（Windows）补配**：`claude mcp add --scope user --transport http langfuse https://jp.cloud.langfuse.com/api/public/mcp --header "Authorization: Basic $B64"`，写入 `C:\Users\Ntwitm\.claude.json`（user 作用域），与 VM 端并行可用
- 已实测 MCP 端点握手（HTTP 200），暴露 **79 个工具**（读 + 写）
- 认证复用 `.env` 里现成的 `LANGFUSE_BASIC_AUTH`（无需新增凭据）

## 3. 官方 MCP server 信息

| 项 | 值 |
|----|----|
| 端点（JP 区域） | `https://jp.cloud.langfuse.com/api/public/mcp` |
| Transport | `streamableHttp`（远程 HTTP，**本机无需安装任何东西**） |
| 认证 | Basic Auth（Authorization 头） |
| 架构 | **无状态**：每次请求独立认证，无 session id |
| 工具 | 79 个，读 + 写（观测/prompt/评分/指标/数据集/评估/仪表盘） |

> 注：早年在 npm 上有个第三方包 `langfuse-mcp-server`（0.0.2-rc.0，作者为 Langfuse 创始人），功能偏 Prompt Management 且长期未更新。**本项目用的是官方内置原生 MCP**（上面表格），与第三方包无关。

## 4. 配置步骤

### 4.1 前置

`picknear/picknear/.env` 已有（本仓库已配好）：
```
LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY / LANGFUSE_BASE_URL / LANGFUSE_BASIC_AUTH
```

### 4.2 提取认证 token

在 `.env` 同级目录执行（`LANGFUSE_BASIC_AUTH` 即 `base64(pk:sk)`）：

```bash
B64=$(sed -n 's/.*LANGFUSE_BASIC_AUTH="\([^"]*\)".*/\1/p' .env)
echo "$B64" | base64 -d   # 应输出 pk-lf-...:sk-lf-...，用于自检
```

> ⚠️ 若 `.env` 曾被 Windows 编辑器存成 CRLF，先 `tr -d '\r'` 再提取：
> ```bash
> B64=$(tr -d '\r' < .env | sed -n 's/.*LANGFUSE_BASIC_AUTH="\([^"]*\)".*/\1/p')
> ```
> 实测 CRLF 会导致认证头携带 `\r` 返回 401，而 compose 解析 `.env` 会自动去 `\r`（所以容器导出正常、命令行 curl 却 401 的典型坑）。

### 4.3 注册 MCP（用户级，推荐）

```bash
claude mcp add --scope user --transport http langfuse \
    "https://jp.cloud.langfuse.com/api/public/mcp" \
    --header "Authorization: Basic $B64"
```

- `--scope user`：写入 `~/.claude.json` 顶层 `mcpServers`，**任何目录启动的 Claude Code 会话都可用**
- 若不加 `--scope`，默认写入**当前项目**作用域（见 §5 踩坑记录）

### 4.4 验证与生效

```bash
claude mcp list     # 预期：langfuse ... (HTTP) - ✔ Connected
claude mcp get langfuse
```

**⚠️ 配置后必须重启 Claude Code 会话**（`/exit` 后重新 `claude`），MCP 工具在会话启动时注入，当前会话看不到新工具。

## 5. 作用域说明（踩坑记录）

| scope | 配置位置 | 何时生效 | 适用 |
|-------|----------|----------|------|
| `user` | `~/.claude.json` 顶层 `mcpServers` | 任何目录的会话 | ✅ **推荐** |
| `project` | `~/.claude.json` 的 `projects.<项目根>` | 仅在该项目目录启动的会话 | 只在 picknear 后端开发时用 |

**实际踩过的坑**（2026-08-05）：用默认 scope 注册后，`claude mcp list` 虽显示 Connected，但**会话从工作区根 `/mnt/hgfs/heima` 启动（非 picknear 项目目录）时，项目级 MCP 不加载**，会话内没有 langfuse 工具。已改为 `user` scope 解决。

## 6. 安全

- `Authorization: Basic <base64>` 包含密钥，只存在 `~/.claude.json`（用户目录），**不进任何 git 仓库**
- 79 个工具默认**读 + 写全开**。若只用来查看调用情况、不写数据，建议在 MCP 客户端配置 **allowlist 限制为只读工具**（`list*` / `get*` / `queryMetrics`），禁掉 `createScore` / `createPrompt` / `delete*` 等写操作
- `submitFeedback` 工具用于向 Langfuse 团队提交反馈，调用前会要求展示完整反馈内容并获用户确认

## 7. 排查

| 现象 | 原因 | 处理 |
|------|------|------|
| `claude mcp list` 显示 Connected 但会话内无工具 | 项目级 scope + 会话 cwd 不匹配 | 改 `user` scope，重启会话 |
| curl MCP 端点返回 401 `Authentication failed` | 认证头里带了 `\r`（CRLF）或 token 提取错误 | 用 §4.2 的 `sed -n 's/...\1/p'` 模式 + `tr -d '\r'` |
| 想知道端点是否可达 | 发 MCP initialize 请求 | `curl -X POST <endpoint>` 带 Basic 头 + `initialize` 方法，返回 200 即通 |

## 8. 后续

使用场景、工具清单、常用查询见 **[Langfuse MCP 使用指南](./Langfuse MCP 使用指南.md)**。若后续要降低安全面，可随时 `claude mcp remove langfuse` 移除。
