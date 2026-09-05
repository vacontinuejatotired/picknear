# Langfuse CLI 使用指南（命令行替代 MCP）

> **版本**: v1.0
> **最后更新**: 2026-09-03
> **相关文档**: [Langfuse MCP 接入与使用指南](./Langfuse MCP 接入与使用指南.md)（MCP 保留，两者并存）、[Langfuse 云接入说明](./Langfuse云接入说明.md)（OTLP 链路/配额）
> **工具**: Langfuse 官方 CLI `langfuse/langfuse-cli`（npm `langfuse-cli`）

---

## 1. 这是什么

`langfuse-cli` 是 Langfuse 官方命令行工具，用 `langfuse api <resource> <action>` 通用封装 Langfuse REST API，在终端就能查观测数据、管 prompt、看评分——**替代 Claude Code 会话里的 Langfuse MCP 工具**。MCP server 保留，两条入口并存；CLI 走自有凭据，不占 MCP 连接。

## 2. 安装（已完成 ✅，Windows 主机）

```bash
npm install -g langfuse-cli        # → 1.2.0，装到 E:\nodejs\global（已在 PATH）
langfuse --version                  # 1.2.0
```

- 要求 Node ≥ 20（主机 v24 满足）；npm 全局前缀 `E:\nodejs\global` 已在 PATH，装完即可用
- 若个别会话找不到命令，重启该终端让 PATH 生效

## 3. 凭据（自动读后端 .env，零配置）

CLI 认 `LANGFUSE_PUBLIC_KEY` / `LANGFUSE_SECRET_KEY` / `LANGFUSE_BASE_URL`（或 `LANGFUSE_HOST`）。后端 `picknear/picknear/.env` 已配齐这三项，无需新增凭据。

- **推荐**：用封装脚本 `lf`（自动加载 .env）——见 §4
- 裸 CLI 用法二选一：

```bash
cd picknear/picknear && langfuse --env .env api observations list --limit 2
# 或先 export 三项再跑（Git Bash）:
source picknear/picknear/.env # 或手动 export LANGFUSE_PUBLIC_KEY=…
```

## 4. 封装命令 `lf`（推荐日常入口）

脚本位置：`picknear/scripts/langfuse/lf`（纯 bash，Git Bash 与 VM Linux 都能跑）。

```bash
LF=/e/heima/picknear/scripts/langfuse/lf   # 或加到 PATH
```

| 子命令 | 动作 → 官方命令 | 实测示例 |
|--------|----------------|----------|
| `lf traces [--limit N] [--json]` | 最近活动（**v4 下 trace 列表走 observations v2 端点**） | `lf traces --limit 5` |
| `lf obs [--trace <id>] [--limit N]` | observations list；`--trace` = `--trace-id` 别名 | `lf obs --trace 9de3…`、`lf obs --type GENERATION --limit 10` |
| `lf metrics [N天] [--json]` | metrics v2 聚合（默认近 7 天调用量/token/成本） | `lf metrics 3` |
| `lf prompts [list \| get <name>]` | prompts v2 管理 | `lf prompts`、`lf prompts get agent.prompt.planner` |
| `lf scores [--limit N]` | scores v3 列表 | `lf scores --limit 5` |
| `lf raw <args…>` | 原样透传 `langfuse <args…>`（完整能力兜底） | `lf raw api health get`、`lf raw api help` |
| `lf help` | 用法速览 | |

- 退出码透传官方 CLI（见 §7），脚本自身无额外状态
- 实测输出为纯 JSON（`{"data":[…]}`），可接 `| jq` 或 `node -e` 加工

## 5. MCP ↔ CLI 能力对照

| MCP 工具 | CLI 等价 | 备注 |
|----------|----------|------|
| `listTraces` | `lf traces` | v4 已无独立 traces v3 端点，列表走 observations v2 |
| `listObservations` | `lf obs` | 支持 `--trace-id`/`--type`/`--from-start-time`/`--is-root-observation` 等 |
| `getObservation`（展开单条） | `lf obs --trace <id>` | v3 单条 get 已被 CLI 拦截，用按 trace 过滤看整棵树 |
| `queryMetrics` | `lf metrics [N天]` | metrics v2；自定义查询见 §6 |
| `listPrompts` | `lf prompts` | |
| `getPrompt` | `lf prompts get <name>` | |
| `createScore` / 评分 | `lf raw api scores create …` | 需 `--body-json`（见 §6） |
| `listScores` | `lf scores` | 走 v3 scores |
| `getHealth` | `lf raw api health get` | |

## 6. 探索全部能力 & Langfuse v4 关键怪癖

**探索**：`langfuse api help`（资源清单）→ `langfuse api help <resource> <action>`（参数）→ `langfuse api schema --json`（机器可读全命令 schema，155KB）。

**⚠️ v4 云端 deprecated 拦截（实测，CLI 直接拒绝执行）**：Langfuse Cloud 已升 v4，旧的 v3 端点被 CLI 拦截并在 **2026-11-16 删除**——

- `GET /api/public/traces`（trace 列表）→ 拦截，用 `GET /api/public/v2/observations`
- `GET /api/public/observations/{id}`（单条 get）→ 拦截，用 v2 observations 过滤

其余常态端点均已 v2/v3：`observations list`（v2）、`prompts *`（v2）、`scores list`（v3）、`metrics get`（v2）、`health get`。

**metrics v2 查询结构**（`lf raw api metrics get --query '<json>'`；`--query` 必填）：

```json
{
  "view": "observations",
  "fromTimestamp": "2026-09-01T00:00:00.000Z",
  "toTimestamp": "2026-09-04T00:00:00.000Z",
  "metrics": [
    {"measure": "count", "aggregation": "count"},
    {"measure": "inputTokens", "aggregation": "sum"},
    {"measure": "outputTokens", "aggregation": "sum"},
    {"measure": "totalTokens", "aggregation": "sum"},
    {"measure": "totalCost", "aggregation": "sum"}
  ]
}
```

实测结论：`metrics[]` 元素用 `measure + aggregation`（非 `type/field`）；时间范围必传 `fromTimestamp/toTimestamp`；维度只认白名单属性（如 `startTimeMonth`、`type`、`name`、`toolNames`），不能用 `startTime` 做日期分组。免费档 `totalCost` 常为 0（无计费模型）。

**分页**：列表加 `--all`（自动翻页，`--max-items` 封顶）；`--json` 输出完整 envelope（status/headers/body）。

## 7. 退出码（agent 可直接用）

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 1 | 内部错误 |
| 2 | 命令/参数错（未发请求） |
| 3 | 凭据缺失/错误（未发请求） |
| 4 | 网络/DNS/TLS 失败 |
| 5 | API 返回非成功状态（响应仍打印） |
| 6 | 本地文件/契约失败 |

## 8. 排查

| 现象 | 原因/处理 |
|------|-----------|
| `langfuse: command not found` | 全局 bin 在 `E:\nodejs\global`，重启终端/检查 PATH |
| 401 认证失败 | `.env` 三键值有无；`lf` 自动读无需 cd；裸 CLI 需在库内目录或手动 export |
| "Cannot call deprecated API operation" | 在对 v3 端点（traces list / observations get），改用 §6 的 v2 等价命令 |
| 想看请求而不执行 | 命令尾加 `--curl`（打印 curl 原文，不发送） |
| CRLF 污染 .env | `.env` 需 LF；`lf` 内部已 `tr -d '\r'` 容错 |

## 9. 速抄（真实可跑）

```bash
LF=/e/heima/picknear/scripts/langfuse/lf
bash "$LF" traces --limit 5                       # 最近活动
bash "$LF" obs --type GENERATION --limit 10       # 最近 10 条生成（LLM 调用）
bash "$LF" obs --trace 9de3408e38986dbe499af1668e31a4b8   # 展开一条完整 trace
bash "$LF" metrics 7                               # 近 7 天调用量/token/成本
bash "$LF" prompts get agent.prompt.planner        # 看某提示词当前 production 版本
bash "$LF" scores --limit 5                        # 最近评分（评测结果）
```