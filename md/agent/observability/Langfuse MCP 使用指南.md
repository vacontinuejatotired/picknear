# Langfuse MCP 使用指南

> **版本**: v1.0
> **最后更新**: 2026-08-05
> **上游文档**: [Langfuse MCP 接入说明](./Langfuse MCP 接入说明.md)（配置方法）、[Agent全链路观测架构设计](./Agent全链路观测架构设计.md)（span 模型）
> **用途**: 接入 Langfuse MCP 后，Claude Code 会话内直接查询观测数据——查看调用情况、验证 span 结构、核对 token 用量与成本

---

## 1. 能力总览（79 个工具，实测 2026-08-05）

| 能力组 | 常用工具 | 用途 |
|--------|----------|------|
| **观测 / trace** | `listObservations`、`getObservation`、`getObservationFilterSchema/Values` | 查 trace 与 span（含输入/输出/usage/耗时） |
| **指标 / 分析** | `queryMetrics`、`getMetricsSchema` | token 用量、成本、延迟、错误率等聚合分析 |
| **Prompt 管理** | `listPrompts`、`getPrompt` | 看项目里的 prompt 版本与内容 |
| **评分 / 反馈** | `listScores`、`getScore`、`createScore`、`listScoreConfigs` | 给 trace/observation 打质量分 |
| **数据集 / 实验** | `listDatasets`、`listDatasetRuns`、`listExperiments` | 评估集与实验运行 |
| **健康检查** | `getHealth` | 确认 Langfuse 云端可用 |
| 其他（注释/队列/评估器/仪表盘/模型定义） | `listComments`、`listEvaluators`、`listDashboards`、`listModels` | 扩展能力，按需使用 |

> 全部工具名以实际 `tools/list` 返回为准（本文档列出的是高频项）。工具多为**读 + 写**双开；只读场景建议在客户端配置 allowlist（见接入说明 §6）。

## 2. 高频场景（直接跟 Claude 说即可）

### 2.1 "看看最近的调用情况"

→ `listObservations`（按时间范围过滤）：
- 最近有哪些 trace / span
- 每个 span 的类型（SPAN / GENERATION / EVENT）、名称、耗时
- 哪条 trace 出现 ERROR 级观测（异常排查入口）

### 2.2 "展开某条 trace 看完整结构"

→ `listObservations`（传 `traceId`），字段带 `parentObservationId`，按层级拼成树。
预期结构（M1 已验证样张）：

```
agent.session  (SPAN, 根)
├── agent.prompt_hook  (SPAN)
└── agent.phase1  (SPAN)
    ├── chat qwen-plus  (GENERATION, 含 in/out token)
    └── agent.decision  (SPAN)
```

带工具/子任务的链路会更深：`agent.round`、`agent.subagent`、`agent.tool_*`、Guard 投票（平铺属性）。

> 注：MCP 工具中**没有独立的 `getTrace`**（实测 79 工具不含），查完整 trace 用 `listObservations` 按 `traceId` 过滤即可；或用 REST API 兜底（见 §3）。

### 2.3 "这个月的 token 用量 / 成本多少"

→ `queryMetrics`（view=observations）+ `getMetricsSchema`：
- 按天聚合的 `totalTokens` / `totalCost`
- 按 model 分组的用量占比
- 按 userId 分组的调用量

### 2.4 "看某个 span 的输入输出 / 工具参数"

→ `getObservation`（传 observation id，按需 `fields`）：
- 输入/输出、metadata、模型参数、usage 明细
- 若要完整大字段（metadata 等）需 `expandMetadataKeys`

### 2.5 "给这条调用打个分"

→ `createScore`（traceId + 名称 + 值 + 可选 configId）：
- 人工复核结果写入 Langfuse，后续可做评估
- ⚠️ 写操作，若配置了只读 allowlist 则不可用

## 3. REST API 兜底（MCP 未覆盖时）

MCP 本质封装 Langfuse 公共 API。一条 curl 即可查（无需 MCP）：

```bash
cd /mnt/hgfs/heima/picknear/picknear
PK=$(sed -n 's/.*LANGFUSE_PUBLIC_KEY="\([^"]*\)".*/\1/p' .env)
SK=$(sed -n 's/.*LANGFUSE_SECRET_KEY="\([^"]*\)".*/\1/p' .env)

# 最近 5 条 trace
curl -su "$PK:$SK" "https://jp.cloud.langfuse.com/api/public/traces?limit=5"

# 某条 trace 的完整结构（含 observations 树）
curl -su "$PK:$SK" "https://jp.cloud.langfuse.com/api/public/traces/{traceId}"
```

- REST API 有**完整的 trace 端点**（`GET /api/public/traces/{id}` 含 `observations` 数组），比 MCP 更适合拿整棵树
- 认证 `-u pk:sk` 已实测返回 200

## 4. 使用边界与免费档注意事项

| 项 | 说明 |
|----|------|
| 读取配额 | 读 trace/observation/metrics 走公共 API，**不消耗** 50,000 units/月 的摄取配额（units 计的是写） |
| 删除 API | 免费档 50 req/day，`delete*` 工具慎用 |
| 只读安全 | 默认读+写全开；演示/交付场景建议只读 allowlist，避免误写评分/prompt |
| 网页端并存 | MCP 适合快速查证；网页端（jp.cloud.langfuse.com）仍是可视化浏览的主入口，两者不冲突 |

## 5. 与观测文档的分工

| 文档 | 内容 |
|------|------|
| [Agent全链路观测架构设计](./Agent全链路观测架构设计.md) | 为什么这么设计：span 模型、AgentTracer API、时序契约 |
| [Langfuse云接入说明](./Langfuse云接入说明.md) | 怎么把数据送到 Langfuse：OTLP 配置、冒烟测试、配额 |
| [Langfuse MCP 接入说明](./Langfuse MCP 接入说明.md) | 怎么让 Claude Code 连上 Langfuse |
| **本指南** | 连上之后怎么查：工具清单、常用场景、REST 兜底 |
